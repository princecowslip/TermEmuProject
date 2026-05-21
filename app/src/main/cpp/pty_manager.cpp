// pty_manager.cpp
// Low-level pseudoterminal management: forkpty, resize, wait, kill.

#include "pty_manager.h"

#include <cerrno>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <pty.h>       // forkpty — bionic provides this via <pty.h> on NDK r29
#include <android/log.h>
#include <thread>
#include <chrono>

#define LOG_TAG "PtyManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── pty_create ──────────────────────────────────────────────────────────────

PtyResult pty_create(const char* command,
                     const std::vector<std::string>& args)
{
    PtyResult result;

    // Build the null-terminated argv array
    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (const auto& arg : args) {
        argv.push_back(const_cast<char*>(arg.c_str()));
    }
    argv.push_back(nullptr);

    // Initial terminal size — will be updated via pty_resize once the view measures
    struct winsize ws{};
    ws.ws_col = 80;
    ws.ws_row = 24;

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, nullptr, nullptr, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        return result; // both fields remain -1
    }

    if (pid == 0) {
        // ── Child process ──────────────────────────────────────────────────

        // Set up a minimal environment suitable for a login shell
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);

        // Configure termios: raw-ish settings for proper signal handling
        struct termios tios{};
        tcgetattr(STDIN_FILENO, &tios);
        tios.c_lflag |= ISIG;   // allow Ctrl+C to send SIGINT
        tcsetattr(STDIN_FILENO, TCSANOW, &tios);

        execvp(command, argv.data());

        // execvp only returns on failure
        LOGE("execvp(%s) failed: %s", command, strerror(errno));
        _exit(127);
    }

    // ── Parent process ─────────────────────────────────────────────────────

    // Keep the master fd in blocking mode so that Java's FileInputStream.read()
    // blocks correctly on the reader thread.  Non-blocking would cause EAGAIN to
    // surface as an IOException, terminating the read loop prematurely.
    // Graceful teardown is handled by interrupt() + close() in TerminalSession.destroy().
    result.master_fd = master_fd;
    result.pid       = static_cast<int>(pid);
    LOGI("pty_create: pid=%d, master_fd=%d", result.pid, result.master_fd);
    return result;
}

// ── pty_resize ─────────────────────────────────────────────────────────────

void pty_resize(int master_fd, unsigned short cols, unsigned short rows)
{
    struct winsize ws{};
    ws.ws_col = cols;
    ws.ws_row = rows;
    if (ioctl(master_fd, TIOCSWINSZ, &ws) < 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

// ── pty_kill ───────────────────────────────────────────────────────────────

void pty_kill(int pid)
{
    if (pid <= 0) return;
    if (kill(pid, SIGKILL) < 0) {
        LOGE("kill(%d, SIGKILL) failed: %s", pid, strerror(errno));
    }
}

// ── pty_wait ───────────────────────────────────────────────────────────────

int pty_wait(int pid)
{
    if (pid <= 0) return -1;

    int status = 0;
    constexpr int kMaxAttempts = 200;
    for (int i = 0; i < kMaxAttempts; ++i) {
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) {
            return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        }
        if (result < 0) {
            LOGE("waitpid(%d) error: %s", pid, strerror(errno));
            return -1;
        }
        // Process hasn't exited yet — sleep 50 ms and retry
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    LOGE("pty_wait(%d): timeout after %d attempts", pid, kMaxAttempts);
    return -1;
}

// ── pty_close_fd ───────────────────────────────────────────────────────────

void pty_close_fd(int fd)
{
    if (fd >= 0) {
        if (close(fd) < 0) {
            LOGE("close(%d) failed: %s", fd, strerror(errno));
        }
    }
}
