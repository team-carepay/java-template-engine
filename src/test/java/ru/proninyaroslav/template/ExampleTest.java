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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, data);
        assertEquals("Hello, John", out.toString());
    }

    @Test
    public void testDataMap() throws Exception {
        Template template = new Template("example");
        template.parse("Hello {{ .firstName }}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        template.execute(out, Map.of("firstName", "John", "lastName", "Doe"));
        assertEquals("Hello John", out.toString());
    }

    static class Data {
        private List<Map<String,String>> recipientData = new ArrayList<>();

        public List<Map<String, String>> getRecipientData() {
            return recipientData;
        }
    }
}
