package com.github.tomakehurst.wiremock.matching;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;
import static com.google.common.collect.Iterables.tryFind;
import static java.util.Arrays.asList;

public class StringValuePatternJsonDeserializer extends JsonDeserializer<StringValuePattern> {

    private static final Map<String, Class<? extends StringValuePattern>> PATTERNS =
        new ImmutableMap.Builder<String, Class<? extends StringValuePattern>>()
            .put("equalTo", EqualToPattern.class)
            .put("equalToJson", EqualToJsonPattern.class)
            .put("matchesJsonPath", MatchesJsonPathPattern.class)
            .put("equalToXml", EqualToXmlPattern.class)
            .put("matchesXPath", MatchesXPathPattern.class)
            .put("contains", ContainsPattern.class)
            .put("matches", RegexPattern.class)
            .put("doesNotMatch", NegativeRegexPattern.class)
            .put("anything", AnythingPattern.class)
            .build();

    @Override
    public StringValuePattern deserialize(JsonParser parser, DeserializationContext context) throws IOException, JsonProcessingException {
        JsonNode rootNode = parser.readValueAsTree();

        if (isAbsent(rootNode)) {
            return StringValuePattern.ABSENT;
        }

        Class<? extends StringValuePattern> patternClass = findPatternClass(rootNode);
        if (patternClass.equals(EqualToJsonPattern.class)) {
            return deserializeEqualToJson(rootNode);
        } else if (patternClass.equals(MatchesXPathPattern.class)) {
            return deserialiseMatchesXPathPattern(rootNode);
        }

        Constructor<? extends StringValuePattern> constructor = findConstructor(patternClass);

        Map.Entry<String, JsonNode> entry = rootNode.fields().next();
        String operand = entry.getValue().textValue();
        try {
            return constructor.newInstance(operand);
        } catch (Exception e) {
            return throwUnchecked(e, StringValuePattern.class);
        }
    }

    private EqualToJsonPattern deserializeEqualToJson(JsonNode rootNode) throws JsonMappingException {
        if (!rootNode.has("equalToJson")) {
            throw new JsonMappingException(rootNode.toString() + " is not a valid comparison");
        }

        String operand = rootNode.findValue("equalToJson").textValue();
        boolean ignoreArrayOrder = fromNullable(rootNode.findValue("ignoreArrayOrder"));
        boolean ignoreExtraElements = fromNullable(rootNode.findValue("ignoreExtraElements"));

        return new EqualToJsonPattern(operand, ignoreArrayOrder, ignoreExtraElements);
    }

    private MatchesXPathPattern deserialiseMatchesXPathPattern(JsonNode rootNode) throws JsonMappingException {
        if (!rootNode.has("matchesXPath")) {
            throw new JsonMappingException(rootNode.toString() + " is not a valid comparison");
        }

        String operand = rootNode.findValue("matchesXPath").textValue();
        JsonNode namespacesNode = rootNode.findValue("xPathNamespaces");

        Map<String, String> namespaces = namespacesNode != null ?
            toNamespaceMap(namespacesNode) :
            Collections.<String, String>emptyMap() ;

        return new MatchesXPathPattern(operand, namespaces);
    }

    private static Map<String, String> toNamespaceMap(JsonNode namespacesNode) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Iterator<Map.Entry<String, JsonNode>> fields = namespacesNode.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            builder.put(field.getKey(), field.getValue().textValue());
        }

        return builder.build();
    }

    private static boolean fromNullable(JsonNode node) {
        return node != null && node.asBoolean();
    }

    @SuppressWarnings("unchecked")
    private static Constructor<? extends StringValuePattern> findConstructor(Class<? extends StringValuePattern> clazz) {
        Optional<Constructor<?>> optionalConstructor =
            tryFind(asList(clazz.getDeclaredConstructors()), new Predicate<Constructor<?>>() {
                @Override
                public boolean apply(Constructor<?> input) {
                    return input.getParameterTypes().length == 1 &&
                        input.getGenericParameterTypes()[0].equals(String.class);
                }
            });

        if (!optionalConstructor.isPresent()) {
            throw new IllegalStateException("Constructor for " + clazz.getSimpleName() + " must have a single string argument constructor");
        }

        return (Constructor<? extends StringValuePattern>) optionalConstructor.get();
    }

    private static boolean isAbsent(JsonNode rootNode) {
        for (Map.Entry<String, JsonNode> node: ImmutableList.copyOf(rootNode.fields())) {
            if (node.getKey().equals("absent")) {
                return true;
            }
        }

        return false;
    }

    private static Class<? extends StringValuePattern> findPatternClass(JsonNode rootNode) throws JsonMappingException {
        for (Map.Entry<String, JsonNode> node: ImmutableList.copyOf(rootNode.fields())) {
            Class<? extends StringValuePattern> patternClass = PATTERNS.get(node.getKey());
            if (patternClass != null) {
                return patternClass;
            }
        }

        throw new JsonMappingException(rootNode.toString() + " is not a valid comparison");
    }
}
