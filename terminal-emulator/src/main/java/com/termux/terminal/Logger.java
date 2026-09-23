package com.termux.terminal;

import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {

    private static void log(int level, TerminalSessionClient client, String logTag, String message) {
        if (client != null) {
            switch (level) {
                case 0: client.logError(logTag, message); break;
                case 1: client.logWarn(logTag, message); break;
                case 2: client.logInfo(logTag, message); break;
                case 3: client.logDebug(logTag, message); break;
                default: client.logVerbose(logTag, message); break;
            }
        } else {
            switch (level) {
                case 0: Log.e(logTag, message); break;
                case 1: Log.w(logTag, message); break;
                case 2: Log.i(logTag, message); break;
                case 3: Log.d(logTag, message); break;
                default: Log.v(logTag, message); break;
            }
        }
    }

    public static void logError(TerminalSessionClient client, String logTag, String message) {
        log(0, client, logTag, message);
    }

    public static void logWarn(TerminalSessionClient client, String logTag, String message) {
        log(1, client, logTag, message);
    }

    public static void logInfo(TerminalSessionClient client, String logTag, String message) {
        log(2, client, logTag, message);
    }

    public static void logDebug(TerminalSessionClient client, String logTag, String message) {
        log(3, client, logTag, message);
    }

    public static void logVerbose(TerminalSessionClient client, String logTag, String message) {
        log(4, client, logTag, message);
    }

    public static void logStackTraceWithMessage(TerminalSessionClient client, String tag, String message, Throwable throwable) {
        logError(client, tag, getMessageAndStackTraceString(message, throwable));
    }

    public static String getMessageAndStackTraceString(String message, Throwable throwable) {
        if (message == null && throwable == null)
            return null;
        else if (message != null && throwable != null)
            return message + ":\n" + getStackTraceString(throwable);
        else if (throwable == null)
            return message;
        else
            return getStackTraceString(throwable);
    }

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return null;

        String stackTraceString = null;

        try {
            StringWriter errors = new StringWriter();
            PrintWriter pw = new PrintWriter(errors);
            throwable.printStackTrace(pw);
            pw.close();
            stackTraceString = errors.toString();
            errors.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return stackTraceString;
    }

}
