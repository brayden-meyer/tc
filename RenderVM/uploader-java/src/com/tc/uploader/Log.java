package com.tc.uploader;

import java.io.*;
import java.text.DateFormat;
import java.util.Date;

public class Log {

    private static File file;
    private static FileWriter fileWriter;

    static {
        file = new File(Uploader.DATA_STORE_DIR.toPath() + "\\uploader.log");
    }

    private static void log(LogType type, String message, Object... messageArgs) {
        if (fileWriter == null) {
            try {
                fileWriter = new FileWriter(file, true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            fileWriter.append(String.format("[%s] %s: %s\n",
                    DateFormat.getDateTimeInstance(0, 0).format(new Date()),
                    type.getPrefix(),
                    String.format(message, messageArgs)));

            fileWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void info(String message, Object... messageArgs) {
        log(LogType.INFO, message, messageArgs);
    }

    public static void error(String message, Object... messageArgs) {
        log(LogType.ERROR, message, messageArgs);
    }

    public static void exception(Exception e) {
        StringWriter stringWriter = new StringWriter();
        e.printStackTrace(new PrintWriter(stringWriter));
        log(LogType.EXCEPTION, stringWriter.toString());
    }

    private enum LogType {
        INFO("INFO"),
        ERROR("ERROR"),
        EXCEPTION("EXCEPTION");

        private String prefix;

        LogType(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }
}
