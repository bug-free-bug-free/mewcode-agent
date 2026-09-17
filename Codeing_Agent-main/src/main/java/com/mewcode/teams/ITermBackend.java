// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * iTerm2 backend for spawning teammates in separate iTerm2 tabs.
 */
public final class ITermBackend {

    private static final Logger log = Logger.getLogger(ITermBackend.class.getName());

    private ITermBackend() {}

    public static String spawnITermTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String tabName = teamName + "-" + memberName;
        String escapedCmd = cliCommand.replace("\"", "\\\"");
        String script = """
                tell application "iTerm2"
                    tell current window
                        create tab with profile "Default"
                        tell current session of current tab
                            set name to "%s"
                            write text "%s"
                        end tell
                    end tell
                end tell
                """.formatted(tabName, escapedCmd);

        var pb = new ProcessBuilder("osascript", "-e", script);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn iTerm2 tab: " + tabName);
        }
        return tabName;
    }

    public static void stopITermTeammate(String tabName) {
        try {
            String script = """
                    tell application "iTerm2"
                        repeat with w in windows
                            repeat with t in tabs of w
                                if name of current session of t is "%s" then
                                    close t
                                    return
                                end if
                            end repeat
                        end repeat
                    end tell
                    """.formatted(tabName);
            new ProcessBuilder("osascript", "-e", script)
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop iTerm2 teammate: " + e.getMessage());
        }
    }
}
