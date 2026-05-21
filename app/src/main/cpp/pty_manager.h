// pty_manager.h
// Interface for POSIX pseudoterminal creation, management, and teardown.

#pragma once

#include <string>
#include <vector>

/**
 * PtyResult
 *
 * Returned by pty_create(). Contains:
 *  - master_fd : Open file descriptor for the PTY master side.
 *  - pid       : PID of the forked child process, or -1 on failure.
 */
struct PtyResult {
    int master_fd = -1;
    int pid       = -1;
};

/**
 * Create a new PTY pair and fork a child process.
 *
 * Internally calls forkpty(3) to allocate a pseudoterminal, then execs
 * the given command. The caller receives the master fd and child PID.
 *
 * @param command  Absolute path to the executable (e.g. "/bin/bash").
 * @param args     Argument vector; args[0] is conventionally the program name.
 * @return         PtyResult with valid master_fd and pid on success,
 *                 or negative fields on failure.
 */
PtyResult pty_create(const char* command,
                     const std::vector<std::string>& args);

/**
 * Resize the terminal associated with a PTY master fd.
 * Sends SIGWINCH to the foreground process group.
 *
 * @param master_fd  File descriptor of the PTY master.
 * @param cols       New column count.
 * @param rows       New row count.
 */
void pty_resize(int master_fd,
                unsigned short cols,
                unsigned short rows);

/**
 * Send SIGKILL to the specified process.
 * @param pid Target process ID.
 */
void pty_kill(int pid);

/**
 * Block until the process exits, then return its exit code.
 * Uses a WNOHANG polling loop with short sleeps to avoid busy-waiting.
 *
 * @param pid  Target process ID.
 * @return     Exit status as returned by WEXITSTATUS, or -1 on error.
 */
int pty_wait(int pid);

/**
 * Close a raw file descriptor.
 * @param fd File descriptor to close.
 */
void pty_close_fd(int fd);
