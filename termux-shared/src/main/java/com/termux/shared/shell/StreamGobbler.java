/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.termux.shared.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

/**
 * Thread utility class continuously reading from an InputStream.
 *
 * <p>Shell STDOUT and STDERR must be read as quickly as possible to prevent a
 * deadlock where {@code Process.waitFor()} never returns (buffer full, native
 * process paused).
 *
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/Shell.java#L141
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/StreamGobbler.java
 */
@SuppressWarnings({"WeakerAccess"})
public class StreamGobbler extends Thread {
    private static int threadCounter = 0;
    private static int incThreadCounter() {
        synchronized (StreamGobbler.class) {
            int ret = threadCounter;
            threadCounter++;
            return ret;
        }
    }

    @NonNull
    private final String shell;
    @NonNull
    private final InputStream inputStream;
    @NonNull
    private final BufferedReader reader;
    @Nullable
    private final StringBuilder stringWriter;
    @Nullable
    private final Integer mLogLevel;

    private static final String LOG_TAG = "StreamGobbler";

    /**
     * Do not use this for concurrent reading of STDOUT and STDERR into the same
     * StringBuilder since it is not synchronized.
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputString {@link StringBuilder} to append to, or null
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream,
                         @Nullable StringBuilder outputString,
                         @Nullable Integer logLevel) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));

        stringWriter = outputString;

        mLogLevel = logLevel;
    }

    @Override
    public void run() {
        String defaultLogTag = Logger.getDefaultLogTag();
        boolean loggingEnabled = Logger.shouldEnableLoggingForCustomLogLevel(mLogLevel);
        if (loggingEnabled)
            Logger.logVerbose(LOG_TAG, "Using custom log level: " + mLogLevel + ", current log level: " + Logger.getLogLevel());

        // keep reading the InputStream until it ends (or an error occurs)
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (loggingEnabled)
                    Logger.logVerboseForce(defaultLogTag + "Command", String.format(Locale.ENGLISH, "[%s] %s", shell, line)); // This will get truncated by LOGGER_ENTRY_MAX_LEN, likely 4KB

                if (stringWriter != null) stringWriter.append(line).append("\n");
            }
        } catch (IOException e) {
            // reader probably closed, expected exit condition
        }

        try {
            reader.close();
        } catch (IOException e) {
            // read already closed
        }
    }
}
