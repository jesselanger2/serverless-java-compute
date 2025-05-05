package edu.yu.cs.com3800;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Util {

    public static byte[] readAllBytesFromNetwork(InputStream in)  {
        try {
            int tries = 0;
            while (in.available() == 0 && tries < 10) {
                try {
                    tries++;
                    Thread.currentThread().sleep(500);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        catch(IOException e){}
        return readAllBytes(in);
    }

    public static byte[] readAllBytes(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int numberRead;
        byte[] data = new byte[40960];
        try {
            while (in.available() > 0 && (numberRead = in.read(data, 0, data.length)) != -1   ) {
                buffer.write(data, 0, numberRead);
            }
        }catch(IOException e){}
        return buffer.toByteArray();
    }

    public static Thread startAsDaemon(Runnable run, String name) {
        Thread thread = new Thread(run, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public static String getStackTrace(Exception e){
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        PrintStream myErr = new PrintStream(bas,true);
        e.printStackTrace(myErr);
        myErr.flush();
        myErr.close();
        return bas.toString();
    }

    public static Logger initializeLogging(String name, Long ID, int port, String type) {
        Logger logger = Logger.getLogger(name + "-" + ID);
        try {
            // Determine the log directory based on the type
            String logDir = "logs/" + switch (type.toUpperCase()) {
                case "BASIC" -> "basic_logs";
                case "SUMMARY" -> "summary_logs";
                case "VERBOSE" -> "verbose_logs";
                default -> throw new IllegalArgumentException("Invalid log type: " + type);
            };
            // Create the log directory if it doesn't exist
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(logDir));
            // Format the log file name
            String logFileName = String.format(
                    "%s/%s-ID-%d-on-Port-%d-Log.txt", logDir, name, ID, port);
            // Create the FileHandler
            FileHandler fileHandler = new FileHandler(logFileName, false);
            fileHandler.setFormatter(new SimpleFormatter()); // Use a simple text formatter
            logger.addHandler(fileHandler);
            logger.setUseParentHandlers(false); // Disable console logging
        } catch (IOException e) {
            e.printStackTrace();
        }
        return logger;
    }
}