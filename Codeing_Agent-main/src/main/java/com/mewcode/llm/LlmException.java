// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class AuthenticationException extends LlmException {

        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static class RateLimitException extends LlmException {
        private final String retryAfter;

        public RateLimitException(String message, String retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public String getRetryAfter() { return retryAfter; }
    }

    public static class ContextTooLongException extends LlmException {
        public ContextTooLongException(String message) {
            super(message);
        }
    }

    public static class NetworkException extends LlmException {
        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
