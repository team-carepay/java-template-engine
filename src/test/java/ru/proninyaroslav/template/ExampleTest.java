package ru.proninyaroslav.template;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExampleTest {
    @Test
    public void testListOfMaps() throws Exception {
        Template template = new Template("example");
        template.parse("Hello{{ for .recipientData }}, {{ .firstName }}{{ end }}");
        ExampleTest.Data data = new ExampleTest.Data();
        data.recipientData.add(Map.of("firstName", "John", "lastName", "Doe"));
        data.recipientData.add(Map.of("firstName", "Jane", "lastName", "Fonda"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, data);
        assertEquals("Hello, John, Jane", out.toString());
    }

    @Test
    public void testDataMap() throws Exception {
        Template template = new Template("example");
        template.parse("Hello {{ .firstName }}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, Map.of("firstName", "John", "lastName", "Doe"));
        assertEquals("Hello John", out.toString());
    }

    @Test
    public void testPipeline() throws Exception {
        Template template = new Template("example");
        template.parse("Hello {{ .email | urlencode }}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, Map.of("email", "test+user@carepay.com"));
        assertEquals("Hello test%2Buser%40carepay.com", out.toString());
    }

    @Test
    public void testDefaultNoValue() throws Exception {
        Template template = new Template("example");
        template.parse("Hello {{ .email | default \"user@host.com\" }}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, Map.of());
        assertEquals("Hello user@host.com", out.toString());
    }

    @Test
    public void testDefaultWithValue() throws Exception {
        Template template = new Template("example");
        template.parse("Hello {{ .email | default \"user@host.com\" }}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, Map.of("email", "john@doe.com"));
        assertEquals("Hello john@doe.com", out.toString());
    }

    static class Data {
        private final List<Map<String,String>> recipientData = new ArrayList<>();

        public List<Map<String, String>> getRecipientData() {
            return recipientData;
        }
    }
}
