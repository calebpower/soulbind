/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.soulbind.connector.forge;

import java.util.List;
import java.util.Map;

/**
 * The page somebody sees, as a string.
 *
 * <p>Pure, so escaping is testable without a browser or a server. That is the
 * whole reason it is not in the transport package: the one property here worth
 * asserting hardest is that nothing a person types can become markup, and a
 * property only reachable by standing a server up is a property that gets
 * asserted once and then not again.
 *
 * <p>No template engine. One form and one result page do not justify a
 * dependency, and a dependency whose job is interpolation is a dependency whose
 * escaping defaults somebody has to go and verify.
 */
public final class SignupPage {

    private SignupPage() {
        throw new AssertionError("no instances");
    }

    /** The form, with any problems above it and the non-secret fields kept. */
    public static String form(String action, List<String> problems, Map<String, String> prefill) {
        StringBuilder out = new StringBuilder();
        out.append(head("Create a forge account"));

        if (!problems.isEmpty()) {
            out.append("<ul class=\"problems\">");
            for (String problem : problems) {
                out.append("<li>").append(escape(problem)).append("</li>");
            }
            out.append("</ul>");
        }

        out.append("<form method=\"post\" action=\"").append(escape(action)).append("\">")
                .append(field("Link code (only the first time)", SignupForm.CODE, "text",
                        prefill))
                .append(field("Username", SignupForm.USERNAME, "text", prefill))
                .append(field("Email", SignupForm.EMAIL, "email", prefill))
                // Never prefilled, even on a redisplay. Putting a password back
                // into the markup writes it into every proxy log and browser
                // cache between here and the person, to save them one retype.
                .append(field("Password", SignupForm.PASSWORD, "password", Map.of()))
                .append(field("Password again", SignupForm.CONFIRM, "password", Map.of()))
                .append("<button type=\"submit\">Create my account</button>")
                .append("</form>");

        return out.append(FOOT).toString();
    }

    /** The outcome. */
    public static String result(SignupResponse response) {
        String kind = response.status() == 200 ? "ok" : response.status() == 403 ? "no" : "wait";
        return head(title(response.status()))
                + "<p class=\"" + kind + "\">" + escape(response.message()) + "</p>"
                + FOOT;
    }

    private static String title(int status) {
        return switch (status) {
            case 200 -> "Done";
            case 403 -> "Not yet";
            default -> "Try again shortly";
        };
    }

    private static String field(
            String label, String name, String type, Map<String, String> prefill) {
        String value = prefill.getOrDefault(name, "");
        return "<label>" + escape(label)
                + "<input name=\"" + escape(name) + "\" type=\"" + escape(type)
                + "\" value=\"" + escape(value) + "\"></label>";
    }

    private static String head(String title) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <style>
                body{font:16px/1.5 system-ui,sans-serif;max-width:34rem;margin:3rem auto;padding:0 1rem}
                label{display:block;margin:1rem 0}input{display:block;width:100%%;padding:.5rem}
                .problems{background:#fdd;padding:1rem;border-radius:.25rem}
                .no{background:#fdd;padding:1rem}.wait{background:#ffd;padding:1rem}
                .ok{background:#dfd;padding:1rem}
                </style></head><body><h1>%s</h1>
                """.formatted(escape(title), escape(title));
    }

    private static final String FOOT = "</body></html>";

    /**
     * Everything a person typed goes through here.
     *
     * <p>Five characters, not three: the two quote forms matter because values
     * are interpolated into attributes, where a bare quotation mark closes the
     * attribute and everything after it is markup.
     */
    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
