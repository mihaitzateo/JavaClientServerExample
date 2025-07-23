import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger; // To track active clients

public class NioClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 12345;
    private static final int NUM_CLIENT_THREADS = 10;
    private static final String MESSAGE_TO_SEND = "Salutations!";
    private static final long RUN_DURATION_SECONDS = 60;
    private static final ConsoleLogger logger = ConsoleLogger.getInstance();

    // To keep track of how many clients are still active for graceful shutdown
    private static final AtomicInteger activeClients = new AtomicInteger(0);

    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_CLIENT_THREADS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < NUM_CLIENT_THREADS; i++) {
            final int clientNum = i + 1;
            activeClients.incrementAndGet(); // Increment active client count
            executor.submit(new ClientTask(clientNum));
        }

        executor.shutdown();
        logger.log("All clients launched. Waiting for them to finish...");

        try {
            // Wait for all clients to finish or timeout
            if (!executor.awaitTermination(RUN_DURATION_SECONDS + 20, TimeUnit.SECONDS)) { // Give extra time for cleanup
                logger.error("Some client threads did not terminate in time. Forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Client main thread interrupted while waiting for termination.", e);
        }
        logger.log("Client application finished. Active clients remaining: " + activeClients.get());
    }

    static class ClientTask implements Runnable {
        private final int clientNumber;
        private final String threadName;
        private Selector selector;
        private SocketChannel clientChannel;
        private long lastMessageSentTime;
        private long clientStartTime;
        private volatile boolean running = true; // Use volatile for thread visibility
        private SelectionKey registrationKey; // Store the initial key for this channel

        // State for managing writes
        private ByteBuffer writeBuffer;
        // Flag to indicate if we are currently waiting for a send operation to complete
        // or if we have just completed a send and are waiting for the next interval.
        private boolean awaitingWriteCompletion = false; 

        public ClientTask(int clientNumber) {
            this.clientNumber = clientNumber;
            this.threadName = "Thread-" + clientNumber;
            // Pre-create the message buffer
            this.writeBuffer = ByteBuffer.wrap((MESSAGE_TO_SEND + "\n").getBytes());
        }

        @Override
        public void run() {
            Thread.currentThread().setName(this.threadName); // Set thread name when task starts
            logger.log(threadName + ": Starting client connection.");
            try {
                selector = Selector.open();
                clientChannel = SocketChannel.open();
                clientChannel.configureBlocking(false); // Non-blocking mode
                
                // *** CRITICAL FIX: INITIATE THE CONNECTION HERE ***
                boolean connected = clientChannel.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT));
                registrationKey = clientChannel.register(selector, SelectionKey.OP_CONNECT); 
                logger.log(threadName + ": Initiated connection to " + SERVER_HOST + ":" + SERVER_PORT + ". Connected immediately: " + connected);

                clientStartTime = System.currentTimeMillis();
                lastMessageSentTime = clientStartTime; // Initialize for first send

                // Add a small initial delay to allow server to fully accept and stabilize connection
                try {
                    Thread.sleep(100); // Increased initial delay for connection stability
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error(threadName + ": Initial sleep interrupted.", e);
                    running = false; // If interrupted during initial sleep, shut down
                }

                while (running && (System.currentTimeMillis() - clientStartTime) < (RUN_DURATION_SECONDS * 1000)) {
                    selector.select(100); // Wait for events (with a timeout)

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (!key.isValid()) {
                            logger.log(threadName + ": SelectionKey became invalid. Channel likely closed or cancelled. Initiating shutdown.");
                            running = false; // Exit the main while loop
                            break; // Exit inner loop as well, outer loop will check 'running'
                        }

                        try {
                            if (key.isConnectable()) {
                                finishConnection(key);
                            }
                            if (key.isWritable()) {
                                // Only write if we have data in the buffer and are awaiting completion
                                if (writeBuffer.hasRemaining() || awaitingWriteCompletion) {
                                     writeMessageWhenReady(key);
                                } else {
                                     // If OP_WRITE fired but we don't have data to send (e.g., already sent),
                                     // remove OP_WRITE interest to prevent repeated firing.
                                     key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                                     logger.log(threadName + ": OP_WRITE fired but no message to send. Removed interest.");
                                }
                            }
                            if (key.isReadable()) {
                                readEcho(key);
                            }
                        } catch (IOException e) {
                            logger.error(threadName + ": I/O error during key processing: " + e.getMessage(), e);
                            key.cancel();
                            try { clientChannel.close(); } catch (IOException ex) { /* ignore */ }
                            running = false; // Stop this client task due to I/O error
                            break; // Exit inner loop due to error
                        }
                    }

                    // Periodically check if it's time to send a new message
                    // We only enable OP_WRITE if we are not currently awaiting a write to complete
                    // and enough time has passed since the last full message send.
                    if (running && clientChannel.isConnected() && !awaitingWriteCompletion && (System.currentTimeMillis() - lastMessageSentTime >= 1000)) {
                        // Prepare buffer for sending the next message
                        writeBuffer.clear();
                        writeBuffer.put((MESSAGE_TO_SEND + "\n").getBytes());
                        writeBuffer.flip();

                        // Set OP_WRITE interest and mark that we are now awaiting write completion
                        registrationKey.interestOps(registrationKey.interestOps() | SelectionKey.OP_WRITE);
                        selector.wakeup(); // Wake up the selector immediately if it's blocking
                        awaitingWriteCompletion = true; // Mark that we've scheduled a send for this cycle

                        logger.log(threadName + ": Scheduled message send for this cycle. Re-enabled OP_WRITE interest.");
                    }
                }
                logger.log(threadName + ": Client main loop completed (time limit or running flag became false).");
            } catch (IOException e) {
                logger.error(threadName + ": Network error in main run loop: " + e.getMessage(), e);
            } finally {
                logger.log(threadName + ": Finally block entered. Calling shutdown.");
                shutdown();
            }
        }

        private void finishConnection(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            try {
                if (channel.isConnectionPending()) {
                    channel.finishConnect(); // Finish the connection handshake
                }
                logger.log(threadName + ": Connected to " + SERVER_HOST + ":" + SERVER_PORT);
                // After connection, register for read and *initial* write.
                // The first message will be sent immediately after connection.
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                awaitingWriteCompletion = true; // Mark that we are now awaiting the first write
                logger.log(threadName + ": Interest ops set to READ | WRITE after connect. Awaiting first write.");
            } catch (IOException e) {
                logger.error(threadName + ": Failed to connect or finish connection: " + e.getMessage(), e);
                key.cancel();
                try { channel.close(); } catch (IOException ex) { /* ignore */ }
                running = false; // Stop this client task
            }
        }

        // Handles the actual writing of the message from the buffer.
        // Assumes writeBuffer is already prepared (flipped) before this is called.
        private void writeMessageWhenReady(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();

            if (writeBuffer.hasRemaining()) {
                int bytesWritten = channel.write(writeBuffer);
                logger.log(threadName + ": Wrote " + bytesWritten + " bytes. Remaining: " + writeBuffer.remaining());

                if (!writeBuffer.hasRemaining()) {
                    logger.log(threadName + ": Sent: \"" + MESSAGE_TO_SEND + "\"");
                    lastMessageSentTime = System.currentTimeMillis();
                    // Once message is fully sent, remove OP_WRITE interest
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    awaitingWriteCompletion = false; // Message fully sent, ready for next interval
                    logger.log(threadName + ": Interest ops changed to READ only after full send. Ready for next interval.");
                }
            } else {
                 // This branch might be hit if OP_WRITE was set but buffer was empty.
                 // This means the previous message was already fully sent.
                 // We can safely remove OP_WRITE interest here.
                 key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                 awaitingWriteCompletion = false; // Ensure this is false
                 logger.log(threadName + ": OP_WRITE fired but nothing to send. Reverted to READ only.");
            }
        }

        private void readEcho(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            ByteBuffer readBuffer = ByteBuffer.allocate(1024); // Allocate new buffer for each read
            int bytesRead = channel.read(readBuffer);

            logger.log(threadName + ": Read attempt resulted in " + bytesRead + " bytes.");

            if (bytesRead == -1) { // Server closed connection
                logger.log(threadName + ": Server disconnected. Bytes read: -1. Initiating client shutdown.");
                key.cancel();
                try { channel.close(); } catch (IOException ex) { /* ignore */ }
                running = false; // Stop this client task
                return;
            }

            if (bytesRead > 0) {
                readBuffer.flip();
                String receivedMessage = new String(readBuffer.array(), 0, bytesRead).trim();
                logger.log(threadName + ": Received: \"" + receivedMessage + "\"");
            }
        }

        private void shutdown() {
            running = false; // Ensure running flag is false
            try {
                if (clientChannel != null) {
                    clientChannel.close();
                }
                if (selector != null) {
                    selector.close();
                }
                logger.log(threadName + ": Client disconnected.");
            } catch (IOException e) {
                logger.error(threadName + ": Error closing client resources: " + e.getMessage(), e);
            } finally {
                activeClients.decrementAndGet(); // Decrement active client count
            }
        }
    }
}

