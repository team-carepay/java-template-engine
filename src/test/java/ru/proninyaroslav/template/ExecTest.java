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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExecTest {
    @Test
    public void testExec() {
        List<TestExec> tests = new ArrayList<>();
        List<Object> ts = new ArrayList<>();
        T t = new T();
        ts.add(t);
        tests.add(new TestExec("empty", "", "", null, false));
        tests.add(new TestExec("text", "hello world", "hello world",
                null, false));
        tests.add(new TestExec(".x", "{{.x}}", "x", ts, false));


        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        FuncMap funcs = new FuncMap();
        Map<String, String> map = new HashMap<>();
        map.put("varargsFunc", "varargsFunc");
        map.put("binaryFunc", "binaryFunc");
        map.put("varargsFuncInt", "varargsFuncInt");
        map.put("execTemplate", "execTemplate");
        funcs.put(map, T.class);

        for (TestExec test : tests) {
            Template tmpl = new Template(test.name);
            tmpl.addFuncs(funcs);
            try {
                tmpl.parse(test.input);
            } catch (Exception e) {
                fail(String.format("%s: %s", test.name, e));
            }
            stream.reset();
            try {
                tmpl.execute(stream, test.data);
            } catch (Exception e) {
                if (test.hasError)
                    System.out.printf("%s: %s\n\t%s\n%n", test.name, test.input, e.getMessage());
                else
                    fail(String.format("%s: unexpected error: %s", test.name, e.getMessage()));
                continue;
            }
            if (test.hasError) {
                System.out.printf("%s: expected error; got none%n", test.name);
                continue;
            }
            String result = stream.toString();
            assertEquals(String.format("%s:", test.name),
                    test.output, result);
        }
    }

    @Test
    public void testDelims() {
        String[] delimPairs = new String[]{
                null, null,
                "{{", "}}",
                "<<", ">>",
                "|", "|",
                "(嗨)", "(世)"
        };
        final String a = "msg";
        final String b = "hello world";
        class RecipientData {
            public final String key = a;
            public final String value = b;

            @Override
            public String toString() {
                return "RecipientData{" +
                        "key=" + key +
                        ",value=" + value+'}';
            }
        }
        RecipientData recipientData = new RecipientData();
        List<RecipientData> val = new ArrayList<>();
        val.add(recipientData);
        for (int i = 0; i < delimPairs.length; i += 2) {
            String text = ".msg";
            String left = delimPairs[i];
            String trueLeft = left;
            String right = delimPairs[i + 1];
            String trueRight = right;
            if (left == null)
                trueLeft = "{{";
            if (right == null)
                trueRight = "}}";
            text = trueLeft + text + trueRight;
            text += trueLeft + "/*comment*/" + trueRight;
            text += trueLeft + "\"" + trueLeft + "\"" + trueRight;
            Template tmpl = new Template("delims");
            tmpl.setDelims(left, right);
            try {
                tmpl.parse(text);
            } catch (Exception e) {
                fail(String.format("delim %s text %s parse err %s",
                        left, text, e));
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            try {
                tmpl.execute(stream, val);
            } catch (Exception e) {
                fail(String.format("delim %s exec err %s",
                        left, e.getMessage()));
            }
            assertEquals(b + trueLeft, stream.toString());
        }
    }

//    @Test
//    public void testMaxExecDepth() {
//        Template tmpl = new Template("tmpl");
//        try {
//            tmpl.parse("{{template `tmpl` .}}");
//        } catch (Exception e) {
//            /* Ignore */
//        }
//        String got = "<null>";
//        ByteArrayOutputStream stream = new ByteArrayOutputStream();
//        try {
//            tmpl.execute(stream, null);
//        } catch (Exception e) {
//            got = e.toString();
//        }
//        stream.reset();
//        final String want = "exceeded maximum template depth";
//        if (!got.contains(want))
//            fail(String.format("got error %s; want %s", got, want));
//    }

    static class T {
        public final boolean truth = true;
        public final int i = 123;
        public final String x = "x";
        public double floatZero;
        /* Nested class */
        public U u = new U("v");
        /* Class with toString() method */
        public final V v = new V(123);
        /* Arrays */
        public int[] iArr = new int[]{1, 2, 3};
        public int[] iArrNull;
        public boolean[] bArr = new boolean[]{true, false};
        public List<Integer> iList = newIList();
        /* Maps */
        public Map<String, Integer> siMap = newSiMap();
        public Map<String, Integer> siMapNull;
        /* Template to test evaluation of templates */
        public final Template tmpl = newTmpl();
        public final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        public int sameName = 1;
        /* Private field; cannot be accessed by template */
        private int priv;

        public static String binaryFunc(String s1, String s2) {
            return String.format("[%s=%s]", s1, s2);
        }

        public static String varargsFunc(String... s) {
            return "<" + Utils.join("+", s) + ">";
        }

        public static String varargsFuncInt(int i, String... s) {
            return i + "=<" + Utils.join("+", s) + ">";
        }

        public static String execTemplate(T t) throws Exception {
            t.tmpl.execute(t.stream, null);

            return t.stream.toString();
        }

        private List<Integer> newIList() {
            List<Integer> list = new ArrayList<>();
            list.add(1);
            list.add(2);
            list.add(3);

            return list;
        }

        private Map<String, Integer> newSiMap() {
            Map<String, Integer> map = new HashMap<>();
            map.put("one", 1);
            map.put("two", 2);
            map.put("three", 3);

            return map;
        }

        private Template newTmpl() {
            Template tmpl = new Template("x");
            try {
                tmpl.parse("test template");
            } catch (Exception e) {
                /* Ignore */
            }

            return tmpl;
        }

        public String meth0() {
            return "m0";
        }

        public int meth1(int a) {
            return a;
        }

        public String meth2(int a, String b) {
            return String.format("meth2: %d %s", a, b);
        }

        public String meth3(Object obj) {
            return String.format("meth3: %s", obj);
        }

        public int sameName() {
            return 1;
        }

        public int sameName(int i) {
            return i;
        }
    }

    static class U {
        public final String v;

        U(String v) {
            this.v = v;
        }
    }

    static class V {
        public final int j;

        V(int j) {
            this.j = j;
        }

        @Override
        public String toString() {
            return "V{" + "j=" + j + '}';
        }
    }

    public static class TestExec {
        final String name;
        final String input;
        final String output;
        final List<Object> data;
        final boolean hasError;

        TestExec(String name, String input, String output,
                 List<Object> data, boolean hasError) {
            this.name = name;
            this.input = input;
            this.output = output;
            this.data = data;
            this.hasError = hasError;
        }
    }
}
