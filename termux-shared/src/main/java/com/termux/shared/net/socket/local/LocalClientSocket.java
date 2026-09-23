package com.termux.shared.net.socket.local;

import androidx.annotation.NonNull;

import com.termux.shared.data.DataUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.jni.models.JniResult;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

/** The client socket for {@link LocalSocketManager}. */
public class LocalClientSocket implements Closeable {

    public static final String LOG_TAG = "LocalClientSocket";

    @NonNull protected final LocalSocketManager mLocalSocketManager;

    @NonNull protected final LocalSocketRunConfig mLocalSocketRunConfig;

    /**
     * The {@link LocalClientSocket} file descriptor.
     * Value will be `>= 0` if socket has been connected and `-1` if closed.
     */
    protected int mFD;

    /** Creation time; also anchors the run-config deadline. */
    protected final long mCreationTime;

    @NonNull protected final PeerCred mPeerCred;

    @NonNull protected final SocketOutputStream mOutputStream;

    @NonNull protected final SocketInputStream mInputStream;

    /** Create a new instance; the socket's creation time (used for the deadline) is set now. */
    LocalClientSocket(@NonNull LocalSocketManager localSocketManager, int fd, @NonNull PeerCred peerCred) {
        mLocalSocketManager = localSocketManager;
        mLocalSocketRunConfig = localSocketManager.getLocalSocketRunConfig();
        mCreationTime = System.currentTimeMillis();
        mOutputStream = new SocketOutputStream();
        mInputStream = new SocketInputStream();
        mPeerCred = peerCred;

        setFD(fd);
        mPeerCred.fillPeerCred(localSocketManager.getContext());
    }

    public synchronized Error closeClientSocket(boolean logErrorMessage) {
        try {
            close();
        } catch (IOException e) {
            Error error = LocalSocketErrno.ERRNO_CLOSE_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(e, mLocalSocketRunConfig.getTitle(), e.getMessage());
            if (logErrorMessage)
                Logger.logErrorExtended(LOG_TAG, error.getErrorLogString());
            return error;
        }

        return null;
    }

    /** Close the client socket that exists at {@code fd}. */
    public static void closeClientSocket(@NonNull LocalSocketManager localSocketManager, int fd) {
        new LocalClientSocket(localSocketManager, fd, new PeerCred()).closeClientSocket(true);
    }

    @Override
    public void close() throws IOException {
        if (mFD >= 0) {
            Logger.logVerbose(LOG_TAG, "Client socket close for \"" + mLocalSocketRunConfig.getTitle() + "\" server: " + getPeerCred().getMinimalString());
            JniResult result = LocalSocketManager.closeSocket(mLocalSocketRunConfig.getLogTitle() + " (client)", mFD);
            if (result == null || result.retval != 0) {
                throw new IOException(JniResult.getErrorString(result));
            }
            // Update fd to signify that client socket has been closed
            setFD(-1);
        }
    }

    /**
     * Read up to {@code data.length} bytes into {@code data}; the count is returned in
     * {@code bytesRead} (0 = EOF). A short read is not an error — fewer bytes may be available,
     * or {@code read()} was interrupted by a signal.
     *
     * <p>Fails if {@link #mCreationTime} + {@link LocalSocketRunConfig#getDeadline()} elapses
     * before the data is read.
     *
     * @return an error on failure, otherwise {@code null}.
     */
    public Error read(@NonNull byte[] data, MutableInt bytesRead) {
        bytesRead.value = 0;

        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(mFD,
                mLocalSocketRunConfig.getTitle());
        }

        JniResult result = LocalSocketManager.read(mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD, data,
            mLocalSocketRunConfig.getDeadline() > 0 ? mCreationTime + mLocalSocketRunConfig.getDeadline() : 0);
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result));
        }

        bytesRead.value = result.intData;
        return null;
    }

    /**
     * Send the data buffer to the file descriptor. Fails if {@link #mCreationTime} +
     * {@link LocalSocketRunConfig#getDeadline()} elapses before all data is sent.
     *
     * @return an error on failure, otherwise {@code null}.
     */
    public Error send(@NonNull byte[] data) {
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(mFD,
                mLocalSocketRunConfig.getTitle());
        }

        JniResult result = LocalSocketManager.send(mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD, data,
            mLocalSocketRunConfig.getDeadline() > 0 ? mCreationTime + mLocalSocketRunConfig.getDeadline() : 0);
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result));
        }

        return null;
    }

    /**
     * Read all available bytes from {@link SocketInputStream} into {@code data}.
     *
     * @param closeStreamOnFinish if {@code true}, the underlying input stream is closed and
     *                            further reads from the socket will fail.
     * @return an error on failure, otherwise {@code null}.
     */
    public Error readDataOnInputStream(@NonNull StringBuilder data, boolean closeStreamOnFinish) {
        int c;
        InputStreamReader inputStreamReader = getInputStreamReader();
        try {
            while ((c = inputStreamReader.read()) > 0) {
                data.append((char) c);
            }
        } catch (IOException e) {
            // The SocketInputStream.read() throws the Error message in an IOException,
            // so just read the exception message and not the stack trace, otherwise it would result
            // in a messy nested error message.
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                mLocalSocketRunConfig.getTitle(), DataUtils.getSpaceIndentedString(e.getMessage(), 1));
        } catch (Exception e) {
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                e, mLocalSocketRunConfig.getTitle(), e.getMessage());
        } finally {
            if (closeStreamOnFinish) {
                try { inputStreamReader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        return null;
    }

    /**
     * Write all of {@code data} to {@link SocketOutputStream}.
     *
     * @param closeStreamOnFinish if {@code true}, the underlying output stream is closed and
     *                            further sends to the socket will fail.
     * @return an error on failure, otherwise {@code null}.
     */
    public Error sendDataToOutputStream(@NonNull String data, boolean closeStreamOnFinish) {

        OutputStreamWriter outputStreamWriter = getOutputStreamWriter();

        try (BufferedWriter byteStreamWriter = new BufferedWriter(outputStreamWriter)) {
            byteStreamWriter.write(data);
            byteStreamWriter.flush();
        } catch (IOException e) {
            // The SocketOutputStream.write() throws the Error message in an IOException,
            // so just read the exception message and not the stack trace, otherwise it would result
            // in a messy nested error message.
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                mLocalSocketRunConfig.getTitle(), DataUtils.getSpaceIndentedString(e.getMessage(), 1));
        } catch (Exception e) {
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                e, mLocalSocketRunConfig.getTitle(), e.getMessage());
        } finally {
            if (closeStreamOnFinish) {
                try {
                    outputStreamWriter.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        return null;
    }

    /** Wrapper for {@link #available(MutableInt, boolean)} that checks deadline. The
     * {@link SocketInputStream} calls this. */
    public Error available(MutableInt available) {
        return available(available, true);
    }

    /**
     * Get available bytes on {@link #mInputStream}; optionally returns {@code null} (not an error)
     * when the run-config deadline has already passed.
     */
    public Error available(MutableInt available, boolean checkDeadline) {
        available.value = 0;

        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(mFD,
                mLocalSocketRunConfig.getTitle());
        }

        if (checkDeadline && mLocalSocketRunConfig.getDeadline() > 0 && System.currentTimeMillis() > (mCreationTime + mLocalSocketRunConfig.getDeadline())) {
            return null;
        }

        JniResult result = LocalSocketManager.available(mLocalSocketRunConfig.getLogTitle() + " (client)", mLocalSocketRunConfig.getFD());
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_CHECK_AVAILABLE_DATA_ON_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(), JniResult.getErrorString(result));
        }

        available.value = result.intData;
        return null;
    }

    /** Set SO_RCVTIMEO to {@link LocalSocketRunConfig#getReceiveTimeout()}. */
    public Error setReadTimeout() {
        if (mFD >= 0) {
            JniResult result = LocalSocketManager.setSocketReadTimeout(mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD, mLocalSocketRunConfig.getReceiveTimeout());
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_READ_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(), mLocalSocketRunConfig.getReceiveTimeout(), JniResult.getErrorString(result));
            }
        }
        return null;
    }

    /** Set SO_SNDTIMEO to {@link LocalSocketRunConfig#getSendTimeout()}. */
    public Error setWriteTimeout() {
        if (mFD >= 0) {
            JniResult result = LocalSocketManager.setSocketSendTimeout(mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD, mLocalSocketRunConfig.getSendTimeout());
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_SEND_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(), mLocalSocketRunConfig.getSendTimeout(), JniResult.getErrorString(result));
            }
        }
        return null;
    }

    /** Get {@link #mFD} for the client socket. */
    public int getFD() {
        return mFD;
    }

    /** Store {@code fd}; values below 0 are normalized to -1 (closed). */
    private void setFD(int fd) {
        if (fd >= 0)
            mFD = fd;
        else
            mFD = -1;
    }

    /** Get {@link #mPeerCred} for the client socket. */
    public PeerCred getPeerCred() {
        return mPeerCred;
    }

    /** Get {@link #mCreationTime} for the client socket. */
    public long getCreationTime() {
        return mCreationTime;
    }

    /** Get {@link #mOutputStream} for the client socket. The stream will automatically close when client socket is closed. */
    public OutputStream getOutputStream() {
        return mOutputStream;
    }

    /** Get {@link OutputStreamWriter} for {@link #mOutputStream} for the client socket. The stream will automatically close when client socket is closed. */
    @NonNull
    public OutputStreamWriter getOutputStreamWriter() {
        return new OutputStreamWriter(getOutputStream());
    }

    /** Get {@link #mInputStream} for the client socket. The stream will automatically close when client socket is closed. */
    public InputStream getInputStream() {
        return mInputStream;
    }

    /** Get {@link InputStreamReader} for {@link #mInputStream} for the client socket. The stream will automatically close when client socket is closed. */
    @NonNull
    public InputStreamReader getInputStreamReader() {
        return new InputStreamReader(getInputStream());
    }

    /** Get a log {@link String} for the {@link LocalClientSocket}. */
    @NonNull
    public String getLogString() {
        StringBuilder logString = new StringBuilder();

        logString.append("Client Socket:");
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("FD", mFD, "-"));
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("Creation Time", mCreationTime, "-"));
        logString.append("\n\n\n");

        logString.append(mPeerCred.getLogString());

        return logString.toString();
    }

    /** Get a markdown {@link String} for the {@link LocalClientSocket}. */
    @NonNull
    public String getMarkdownString() {
        StringBuilder markdownString = new StringBuilder();

        markdownString.append("## ").append("Client Socket");
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("FD", mFD, "-"));
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Creation Time", mCreationTime, "-"));
        markdownString.append("\n\n\n");

        markdownString.append(mPeerCred.getMarkdownString());

        return markdownString.toString();
    }

    /** Wrapper class to allow pass by reference of int values. */
    public static final class MutableInt {
        public int value;

        public MutableInt(int value) {
            this.value = value;
        }
    }

    /** The {@link InputStream} implementation for the {@link LocalClientSocket}. */
    protected class SocketInputStream extends InputStream {
        private final byte[] mBytes = new byte[1];

        @Override
        public int read() throws IOException {
            MutableInt bytesRead = new MutableInt(0);
            Error error = LocalClientSocket.this.read(mBytes, bytesRead);
            if (error != null) {
                throw new IOException(error.getErrorMarkdownString());
            }

            if (bytesRead.value == 0) {
                return -1;
            }

            return mBytes[0];
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            if (bytes == null) {
                throw new NullPointerException("Read buffer can't be null");
            }

            MutableInt bytesRead = new MutableInt(0);
            Error error = LocalClientSocket.this.read(bytes, bytesRead);
            if (error != null) {
                throw new IOException(error.getErrorMarkdownString());
            }

            if (bytesRead.value == 0) {
                return -1;
            }

            return bytesRead.value;
        }

        @Override
        public int available() throws IOException {
            MutableInt available = new MutableInt(0);
            Error error = LocalClientSocket.this.available(available);
            if (error != null) {
                throw new IOException(error.getErrorMarkdownString());
            }
            return available.value;
        }
    }

    /** The {@link OutputStream} implementation for the {@link LocalClientSocket}. */
    protected class SocketOutputStream extends OutputStream {
        private final byte[] mBytes = new byte[1];

        @Override
        public void write(int b) throws IOException {
            mBytes[0] = (byte) b;

            Error error = LocalClientSocket.this.send(mBytes);
            if (error != null) {
                throw new IOException(error.getErrorMarkdownString());
            }
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            Error error = LocalClientSocket.this.send(bytes);
            if (error != null) {
                throw new IOException(error.getErrorMarkdownString());
            }
        }
    }

}
