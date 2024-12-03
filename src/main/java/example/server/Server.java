package example.server;

// Server.java

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.domain.Request;
import example.domain.Response;
import example.domain.configuration.Config;
import example.domain.configuration.PlayerConfiguration;
import example.domain.game.Action;
import example.domain.game.Direction;
import example.domain.game.Player;
import example.game.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<State> state = new AtomicReference<>(new State(List.of(), List.of(), Map.of(), Map.of()));
    private final BlockingQueue<Action> actionsQueue = new LinkedBlockingQueue<>();
    private final Lock stateLock = new ReentrantLock();
    private final Condition stateUpdated = stateLock.newCondition();
    private final Game game;

    private final Collection<PlayerConfiguration> known;

    public Server(Game game, Path path) throws IOException {
        final var config = objectMapper.readValue(Files.readAllBytes(path), Config.class);
        this.known = config.known();
        this.game = game;
        known.forEach((configuration) -> game.add(configuration.player(), game::randomLocation));
        game.render();
    }

    public void start(int port) {
        // Start the commands processing thread
        final var threadProcessCommand = Executors.defaultThreadFactory().newThread(this::processCommands);
        threadProcessCommand.start();

        try (final var serverSocket = new ServerSocket(port)) {
            logger.info("Server started on port {}", port);

            while (!Thread.currentThread().isInterrupted()) {
                final var clientSocket = serverSocket.accept();
                logger.info("Client connected: {}", clientSocket.getRemoteSocketAddress());

                // Start a client connection thread
                Thread.startVirtualThread(() -> handleClientConnection(clientSocket));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            threadProcessCommand.interrupt();
        }
    }

    private void handleClientConnection(Socket clientSocket) {
        try (final var is = clientSocket.getInputStream();
             final var isr = new InputStreamReader(is);
             final var reader = new BufferedReader(isr);
             final var os = clientSocket.getOutputStream();
             final var osr = new OutputStreamWriter(os);
             final var writer = new BufferedWriter(osr)) {
            // handle authorization
            final var line = reader.readLine();
            if (line == null) {
                return;
            }

            final Player.HumanPlayer player;
            final var request = objectMapper.readValue(line, Request.class);
            if (Objects.requireNonNull(request) instanceof Request.Authorize authorize) {
                player = known.stream()
                        .filter(configuration -> configuration.authorize().equals(authorize))
                        .findAny()
                        .map(PlayerConfiguration::player)
                        .orElse(null);
                if (player == null) {
                    final var json = objectMapper.writeValueAsString(new Response.Unauthorized());
                    writer.write(json);
                    writer.newLine();
                    writer.flush();
                    return;
                }

                final var json = objectMapper.writeValueAsString(new Response.Authorized(player));
                writer.write(json);
                writer.newLine();
                writer.flush();
            } else {
                return;
            }

            {
                final var json = objectMapper.writeValueAsString(new Response.StateCave(game.cave()));
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            Thread t1 = Thread.startVirtualThread(() -> handleClientCommands(reader, player));
            Thread t2 = Thread.startVirtualThread(() -> handleClientState(writer, player));
            t1.join();
            t2.join();
        } catch (IOException e) {
            logger.error("Commands processing thread interrupted", e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            logger.error("Commands processing thread interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private void processCommands() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Wait for one second
                Thread.sleep(1000);

                // Process all collected commands
                final var actions = new LinkedList<Action>();
                actionsQueue.drainTo(actions);

                game.step(actions);
                game.statistics();

                final var itemLocations = game.itemLocation().entrySet().stream().map(entry -> new Response.StateLocations.ItemLocation(entry.getKey(), entry.getValue())).toList();
                final var playerLocations = game.playerLocation().entrySet().stream().map(entry -> new Response.StateLocations.PlayerLocation(entry.getKey(), entry.getValue())).toList();

                // Update the state
                stateLock.lock();
                try {
                    state.set(new State(itemLocations, playerLocations, game.playerHealth(), game.playerGold()));
                    // Notify client state threads
                    stateUpdated.signalAll();
                } finally {
                    stateLock.unlock();
                }
            }
        } catch (InterruptedException e) {
            logger.error("Commands processing thread interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private void handleClientCommands(BufferedReader reader, Player.HumanPlayer player) {
        try (reader) {
            while (!Thread.currentThread().isInterrupted()) {
                final var line = reader.readLine();
                if (line == null) {
                    break;
                }

                final var request = objectMapper.readValue(line, Request.class);
                logger.info("Received command {} from {}", request, player);

                if (Objects.requireNonNull(request) instanceof Request.Command(Direction direction)) {
                    actionsQueue.put(new Action(player, direction));
                }
            }
        } catch (IOException | InterruptedException e) {
//            Thread.currentThread().interrupt();
        }
    }

    private void handleClientState(BufferedWriter writer, Player.HumanPlayer player) {
        try (writer) {
            while (!Thread.currentThread().isInterrupted()) {
                stateLock.lock();
                try {
                    stateUpdated.await();
                    // Send the new state to the client
                    final var currentState = state.get();
                    final var playerState = new Response.StateLocations(
                            currentState.itemLocations(),
                            currentState.playerLocations(),
                            currentState.playerHealths().getOrDefault(player, 0),
                            currentState.playerGolds().getOrDefault(player, 0)
                    );
                    final var stateJson = objectMapper.writeValueAsString(playerState);
                    writer.write(stateJson);
                    writer.newLine();
                    writer.flush();
                } finally {
                    stateLock.unlock();
                }
            }
        } catch (IOException | InterruptedException e) {
//            Thread.currentThread().interrupt();
        }
    }

    private record State(List<Response.StateLocations.ItemLocation> itemLocations,
                         List<Response.StateLocations.PlayerLocation> playerLocations,
                         Map<Player, Integer> playerHealths,
                         Map<Player, Integer> playerGolds) {
    }
}
