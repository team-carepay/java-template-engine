/*
 * Copyright (C) 2018 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package ru.proninyaroslav.template;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class Utils {
    static boolean isSpace(final char c) {
        return c == ' ' || c == '\t';
    }

    static boolean isEndOfLine(final char c) {
        return c == '\r' || c == '\n';
    }

    static boolean isAlphaNumeric(final char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    static char unquoteChar(String s, final char quote, final StringBuilder tail) {
        final String err = String.format("malformed character constant: %s", s);
        char c = s.charAt(0);
        if (c == quote && (quote == '\'' || quote == '"')) {
            throw new IllegalArgumentException(err);
        } else if (c != '\\') {
            if (tail != null) {
                tail.setLength(0);
                tail.append(s.substring(1));
            }
            return c;
        }

        /* Escaped character */
        if (s.length() <= 1) {
            throw new IllegalArgumentException(err);
        }
        c = s.charAt(1);
        s = s.substring(2);
        if (tail != null) {
            tail.setLength(0);
            tail.append(s);
        }

        final List<Integer> chars = new ArrayList<>();
        int val;
        switch (c) {
            case 'n':
                return '\n';
            case 't':
                return '\t';
            case 'b':
                return '\b';
            case 'r':
                return '\r';
            case 'f':
                return '\f';
            case '\\':
                return '\\';
            case '\'':
            case '"':
                if (c != quote) {
                    throw new IllegalArgumentException(err);
                }
                return c;
            case 'u':
                final int maxLength = 4;
                if (s.length() < maxLength) {
                    throw new IllegalArgumentException(err);
                }
                val = 0;
                for (int i = 0; i < maxLength; i++) {
                    int n;
                    try {
                        n = unhex(s.charAt(i));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(err);
                    }
                    val = (val << maxLength) | n;
                }
                if (val > Character.MAX_VALUE) {
                    throw new IllegalArgumentException(err);
                }
                if (tail != null) {
                    tail.setLength(0);
                    tail.append(s.substring(maxLength));
                }

                return (char) val;
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
                val = c - '0';
                for (int i = 0; i < s.length(); i++) {
                    int n = s.charAt(i) - '0';
                    if (n >= 0 && n <= 7) {
                        chars.add(n);
                    }
                }
                int length = chars.size();
                for (int n : chars) {
                    val = (val << length + 1) | n;
                }
                if (val > Character.MAX_VALUE) {
                    throw new IllegalArgumentException(err);
                }
                if (tail != null) {
                    tail.setLength(0);
                    tail.append(s.substring(length));
                }

                return (char) val;
            default:
                throw new IllegalArgumentException(err);
        }
    }

    private static int unhex(final char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }

        throw new IllegalArgumentException();
    }

    static String unquote(String s) {
        final int n = s.length();
        final String err = String.format("malformed string constant: %s", s);
        if (n < 2) {
            throw new IllegalArgumentException(err);
        }

        final char quote = s.charAt(0);
        if (quote != s.charAt(n - 1)) {
            throw new IllegalArgumentException(err);
        }
        s = s.substring(1, n - 1);

        if (quote == '`') {
            if (s.indexOf('`') >= 0) {
                throw new IllegalArgumentException(err);
            }
            if (s.indexOf('\r') >= 0) {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    if (s.charAt(i) != '\r') {
                        sb.append(s.charAt(i));
                    }
                }

                return sb.toString();
            }

            return s;
        }
        if (quote != '"' && quote != '\'') {
            throw new IllegalArgumentException(err);
        }
        if (s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(err);
        }

        if (s.indexOf('\\') < 0 && s.indexOf(quote) < 0) {
            switch (quote) {
                case '"':
                    return s;
                case '\'':
                    if (s.length() == 1)
                        return s;
            }
        }

        final StringBuilder sb = new StringBuilder();
        while (s.length() > 0) {
            final StringBuilder tail = new StringBuilder();
            final char c = unquoteChar(s, quote, tail);
            s = tail.toString();
            sb.append(c);
            if (quote == '\'' && s.length() != 0) {
                throw new IllegalArgumentException(err);
            }
        }

        return sb.toString();
    }

    /**
     * returns the string with % replaced by %%, if necessary,
     * so it can be used safely inside a String.format().
     */
    static String doublePercent(String s) {
        if (s.contains("%")) {
            s = s.replaceAll("%", "%%");
        }

        return s;
    }

    static int countChars(final String s, final char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                ++n;
            }
        }

        return n;
    }

    static boolean containsAny(final String s, final String chars) {
        boolean match = false;
        for (char c : chars.toCharArray()) {
            if (s.indexOf(c) >= 0) {
                match = true;
                break;
            }
        }

        return match;
    }

    static boolean isHexConstant(final String s) {
        return s.length() > 2 && s.charAt(0) == '0' &&
                (s.charAt(1) == 'x' || s.charAt(1) == 'X');
    }

    /**
     * Reports whether the value is 'true', in the sense of not
     * the zero of its type, and whether the value has a meaningful truth value.
     */
    static boolean isTrue(final Object val) throws IllegalArgumentException {
        if (val == null) {
            return false;
        }

        boolean truth = true;
        if (val instanceof Boolean) {
            truth = (Boolean) val;
        } else if (val instanceof String) {
            truth = !((String) val).isEmpty();
        } else if (val instanceof Integer) {
            truth = ((Integer) val) > 0;
        } else if (val instanceof Double) {
            truth = ((Double) val) > 0;
        } else if (val instanceof Long) {
            truth = ((Long) val) > 0;
        } else if (val instanceof Collection) {
            truth = !((Collection) val).isEmpty();
        } else if (val instanceof Map) {
            truth = !((Map) val).isEmpty();
        } else if (val.getClass().isArray()) {
            truth = Array.getLength(val) > 0;
        } else if (val instanceof Float) {
            truth = ((Float) val) > 0;
        } else if (val instanceof Short) {
            truth = ((Short) val) > 0;
        } else if (val instanceof Byte) {
            truth = ((Byte) val) > 0;
        }

        return truth;
    }

    static Map<String, String> filesToString(final File... files) throws IOException {
        final Map<String, String> s = new HashMap<>();
        for (final File file : files) {
            s.put(file.getName(), new String(FileUtils.bytes(file)));
        }

        return s;
    }

    public static String join(final CharSequence delimiter, final Iterable<? extends CharSequence> elements) {
        Objects.requireNonNull(delimiter);
        Objects.requireNonNull(elements);
        final StringBuilder joiner = new StringBuilder();
        final Iterator i = elements.iterator();

        while (i.hasNext()) {
            CharSequence cs = (CharSequence) i.next();
            joiner.append(cs);
            if (i.hasNext()) {
                joiner.append(delimiter);
            }
        }

        return joiner.toString();
    }

    public static String join(final CharSequence delimiter, final CharSequence... elements) {
        Objects.requireNonNull(delimiter);
        Objects.requireNonNull(elements);
        final StringBuilder joiner = new StringBuilder();

        for (int i = 0; i < elements.length; i++) {
            final CharSequence cs = elements[i];
            joiner.append(cs);
            if (i + 1 != elements.length) {
                joiner.append(delimiter);
            }
        }

        return joiner.toString();
    }
}

