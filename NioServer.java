import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NioServer {
    private static final int PORT = 12345;
    private static final String ECHO_MESSAGE = "Salutations!";
    private static final long RUN_DURATION_SECONDS = 60; // Server runs for 60 seconds
    private static final ConsoleLogger logger = ConsoleLogger.getInstance();

    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private volatile boolean running = true;
    private final ExecutorService clientHandlerPool = Executors.newCachedThreadPool(); // For handling read/write operations

    public void startServer() {
        try {
            selector = Selector.open();
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress(PORT));
            serverSocketChannel.configureBlocking(false); // Non-blocking mode
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT); // Register for accept events

            logger.log("Server started on port " + PORT + ". Running for " + RUN_DURATION_SECONDS + " seconds...");

            long startTime = System.currentTimeMillis();

            while (running && (System.currentTimeMillis() - startTime) < (RUN_DURATION_SECONDS * 1000)) {
                selector.select(100); // Wait for events (with a timeout)
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        acceptConnection(key);
                    } else if (key.isReadable()) {
                        // Handle read in a separate thread from the pool to avoid blocking the selector loop
                        clientHandlerPool.submit(() -> readAndEcho(key));
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Server error: " + e.getMessage(), e);
        } finally {
            stopServer();
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = ssc.accept();
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ); // Register for read events
            logger.log("Accepted connection from " + clientChannel.getRemoteAddress());
        }
    }

    private void readAndEcho(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            int bytesRead = clientChannel.read(buffer);
            if (bytesRead == -1) { // Client closed connection
                logger.log("Client disconnected: " + clientChannel.getRemoteAddress());
                clientChannel.close();
                key.cancel();
                return;
            }

            if (bytesRead > 0) {
                buffer.flip(); // Prepare buffer for reading
                String receivedMessage = new String(buffer.array(), 0, bytesRead).trim();
                logger.log("Received from " + clientChannel.getRemoteAddress() + ": \"" + receivedMessage + "\"");

                // Echo back
                ByteBuffer writeBuffer = ByteBuffer.wrap((ECHO_MESSAGE + "\n").getBytes());
                while (writeBuffer.hasRemaining()) {
                    clientChannel.write(writeBuffer);
                }
                logger.log("Echoed to " + clientChannel.getRemoteAddress() + ": \"" + ECHO_MESSAGE + "\"");
            }
        } catch (IOException e) {
            logger.error("Error reading/writing from client " + clientChannel + ": " + e.getMessage(), e);
            try {
                clientChannel.close();
                key.cancel();
            } catch (IOException ex) {
                logger.error("Error closing client channel: " + ex.getMessage(), ex);
            }
        }
    }

    public void stopServer() {
        running = false;
        try {
            if (selector != null) {
                selector.close();
            }
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
            }
            clientHandlerPool.shutdown();
            // Corrected logger.error call:
            if (!clientHandlerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.error("Client handler pool did not terminate in time. Forcing shutdown.");
                clientHandlerPool.shutdownNow();
            }
            logger.log("Server stopped.");
        } catch (IOException | InterruptedException e) {
            logger.error("Error stopping server: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        NioServer server = new NioServer();
        server.startServer();
    }
}

