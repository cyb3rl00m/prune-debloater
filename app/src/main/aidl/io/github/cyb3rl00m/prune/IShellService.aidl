package io.github.cyb3rl00m.prune;

import android.os.Bundle;

// Runs inside Shizuku's process as the shell (or root) user.
interface IShellService {
    // Shizuku calls this when the service is unbound. Transaction code is fixed by Shizuku.
    void destroy() = 16777114;

    // Runs one program with the given argv (no shell). Returns exitCode (int), stdout, stderr (String).
    Bundle exec(in String[] argv, long timeoutMillis) = 1;

    // uid the service runs as: 2000 = shell (ADB/Shizuku), 0 = root.
    int uid() = 2;
}
