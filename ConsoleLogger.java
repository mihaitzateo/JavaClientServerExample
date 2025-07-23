import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Semaphore; // For controlled access to the console

public class ConsoleLogger {
    private static ConsoleLogger instance;
    private static final Object lock = new Object(); // For synchronizing instance creation
    private final Semaphore printSemaphore = new Semaphore(1); // Only one thread can print at a time
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private ConsoleLogger() {
        // Private constructor to prevent direct instantiation
    }

    public static ConsoleLogger getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ConsoleLogger();
                }
            }
        }
        return instance;
    }

    public void log(String message) {
        try {
            printSemaphore.acquire(); // Acquire a permit before printing
            System.out.println(String.format("[%s] %s", LocalDateTime.now().format(formatter), message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            System.err.println("ConsoleLogger interrupted: " + e.getMessage());
        } finally {
            printSemaphore.release(); // Release the permit
        }
    }

    // Overloaded method for error logging without a specific Throwable
    public void error(String message) {
        error(message, null); // Call the main error method with null Throwable
    }

    // Main method for error logging with a Throwable
    public void error(String message, Throwable t) {
        try {
            printSemaphore.acquire();
            System.err.println(String.format("[%s] ERROR: %s", LocalDateTime.now().format(formatter), message));
            if (t != null) {
                t.printStackTrace(System.err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ConsoleLogger interrupted during error logging: " + e.getMessage());
        } finally {
            printSemaphore.release();
        }
    }
}

