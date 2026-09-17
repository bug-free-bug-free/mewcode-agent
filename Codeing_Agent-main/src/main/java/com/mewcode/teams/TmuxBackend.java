// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tmux backend for spawning teammates in separate tmux windows.
 */
public final class TmuxBackend {

    private static final Logger log = Logger.getLogger(TmuxBackend.class.getName());

    private TmuxBackend() {}

    public static String spawnTmuxTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String paneName = teamName + "-" + memberName;
        var pb = new ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn tmux window: " + paneName);
        }
        return paneName;
    }

    public static void stopTmuxTeammate(String paneName) {
        try {
            // Send Ctrl-C first
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c")
                    .start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            // Then kill the window
            new ProcessBuilder("tmux", "kill-window", "-t", paneName)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop tmux teammate: " + e.getMessage());
        }
    }
}
