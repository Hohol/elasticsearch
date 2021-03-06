/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.dataframe.extractor;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.fieldcaps.FieldCapabilities;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsDest;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsSource;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.Classification;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.OutlierDetection;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.Regression;
import org.elasticsearch.xpack.core.ml.dataframe.explain.FieldSelection;
import org.elasticsearch.xpack.ml.extractor.ExtractedField;
import org.elasticsearch.xpack.ml.extractor.ExtractedFields;
import org.elasticsearch.xpack.ml.test.SearchHitBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ExtractedFieldsDetectorTests extends ESTestCase {

    private static final String[] SOURCE_INDEX = new String[] { "source_index" };
    private static final String DEST_INDEX = "dest_index";
    private static final String RESULTS_FIELD = "ml";

    private FetchSourceContext sourceFiltering;
    private FetchSourceContext analyzedFields;

    public void testDetect_GivenFloatField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float").build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(1));
        assertThat(allFields.get(0).getName(), equalTo("some_float"));
        assertThat(allFields.get(0).getMethod(), equalTo(ExtractedField.Method.DOC_VALUE));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("some_float", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL));
    }

    public void testDetect_GivenNumericFieldWithMultipleTypes() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_number", "long", "integer", "short", "byte", "double", "float", "half_float", "scaled_float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(1));
        assertThat(allFields.get(0).getName(), equalTo("some_number"));
        assertThat(allFields.get(0).getMethod(), equalTo(ExtractedField.Method.DOC_VALUE));

        assertFieldSelectionContains(fieldExtraction.v2(), FieldSelection.included("some_number",
            new HashSet<>(Arrays.asList("long", "integer", "short", "byte", "double", "float", "half_float", "scaled_float")), false,
            FieldSelection.FeatureType.NUMERICAL));
    }

    public void testDetect_GivenOutlierDetectionAndNonNumericField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_keyword", "keyword").build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
        SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No compatible fields could be detected in index [source_index]." +
            " Supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]."));
    }

    public void testDetect_GivenOutlierDetectionAndFieldWithNumericAndNonNumericTypes() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("indecisive_field", "float", "keyword").build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No compatible fields could be detected in index [source_index]. " +
            "Supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]."));
    }

    public void testDetect_GivenOutlierDetectionAndMultipleFields() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("some_boolean", "boolean")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(3));
        assertThat(allFields.stream().map(ExtractedField::getName).collect(Collectors.toSet()),
            containsInAnyOrder("some_float", "some_long", "some_boolean"));
        assertThat(allFields.stream().map(ExtractedField::getMethod).collect(Collectors.toSet()),
            contains(equalTo(ExtractedField.Method.DOC_VALUE)));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("some_boolean", Collections.singleton("boolean"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("some_float", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.excluded("some_keyword", Collections.singleton("keyword"), "unsupported type; " +
                "supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]"),
            FieldSelection.included("some_long", Collections.singleton("long"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenRegressionAndMultipleFields() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("some_boolean", "boolean")
            .addAggregatableField("foo", "double")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("foo"), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(5));
        assertThat(allFields.stream().map(ExtractedField::getName).collect(Collectors.toList()),
            containsInAnyOrder("foo", "some_float", "some_keyword", "some_long", "some_boolean"));
        assertThat(allFields.stream().map(ExtractedField::getMethod).collect(Collectors.toSet()),
            contains(equalTo(ExtractedField.Method.DOC_VALUE)));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("foo", Collections.singleton("double"), true, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("some_boolean", Collections.singleton("boolean"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("some_float", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("some_keyword", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.included("some_long", Collections.singleton("long"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenRegressionAndRequiredFieldMissing() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("foo"), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("required field [foo] is missing; analysis requires fields [foo]"));
    }

    public void testDetect_GivenRegressionAndRequiredFieldExcluded() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("foo", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[0], new String[] {"foo"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("foo"), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("required field [foo] is missing; analysis requires fields [foo]"));
    }

    public void testDetect_GivenRegressionAndRequiredFieldNotIncluded() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("foo", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]  {"some_float", "some_keyword"}, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("foo"), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("required field [foo] is missing; analysis requires fields [foo]"));
    }

    public void testDetect_GivenFieldIsBothIncludedAndExcluded() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("foo", "float")
            .addAggregatableField("bar", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]  {"foo", "bar"}, new String[] {"foo"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(1));
        assertThat(allFields.stream().map(ExtractedField::getName).collect(Collectors.toList()), contains("bar"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("bar", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.excluded("foo", Collections.singleton("float"), "field in excludes list")
        );
    }

    public void testDetect_GivenFieldIsNotIncludedAndIsExcluded() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("foo", "float")
            .addAggregatableField("bar", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]  {"foo"}, new String[] {"bar"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(1));
        assertThat(allFields.stream().map(ExtractedField::getName).collect(Collectors.toList()), contains("foo"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.excluded("bar", Collections.singleton("float"), "field not in includes list"),
            FieldSelection.included("foo", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenRegressionAndRequiredFieldHasInvalidType() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("foo", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("foo"), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("invalid types [keyword] for required field [foo]; " +
            "expected types are [byte, double, float, half_float, integer, long, scaled_float, short]"));
    }

    public void testDetect_GivenClassificationAndRequiredFieldHasInvalidType() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_float", "float")
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("foo", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildClassificationConfig("some_float"), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("invalid types [float] for required field [some_float]; " +
            "expected types are [boolean, byte, integer, ip, keyword, long, short, text]"));
    }

    public void testDetect_GivenClassificationAndDependentVariableHasInvalidCardinality() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_long", "long")
            .addAggregatableField("some_keyword", "keyword")
            .addAggregatableField("foo", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(SOURCE_INDEX,
            buildClassificationConfig("some_keyword"), false, 100, fieldCapabilities, Collections.singletonMap("some_keyword", 3L));
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("Field [some_keyword] must have at most [2] distinct values but there were at least [3]"));
    }

    public void testDetect_GivenIgnoredField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("_id", "float").build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No compatible fields could be detected in index [source_index]. " +
            "Supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]."));
    }

    public void testDetect_GivenIncludedIgnoredField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("_id", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]{"_id"}, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No field [_id] could be detected"));
    }

    public void testDetect_GivenExcludedFieldIsMissing() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("foo", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]{"*"}, new String[] {"bar"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No field [bar] could be detected"));
    }

    public void testDetect_GivenExcludedFieldIsUnsupported() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("numeric", "float")
            .addAggregatableField("categorical", "keyword")
            .build();
        analyzedFields = new FetchSourceContext(true, null, new String[] {"categorical"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());

        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(1));
        assertThat(allFields.get(0).getName(), equalTo("numeric"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.excluded("categorical", Collections.singleton("keyword"),
                "unsupported type; supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]"),
            FieldSelection.included("numeric", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_ShouldSortFieldsAlphabetically() {
        int fieldCount = randomIntBetween(10, 20);
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            fields.add(randomAlphaOfLength(20));
        }
        List<String> sortedFields = new ArrayList<>(fields);
        Collections.sort(sortedFields);

        MockFieldCapsResponseBuilder mockFieldCapsResponseBuilder = new MockFieldCapsResponseBuilder();
        for (String field : fields) {
            mockFieldCapsResponseBuilder.addAggregatableField(field, "float");
        }
        FieldCapabilitiesResponse fieldCapabilities = mockFieldCapsResponseBuilder.build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(sortedFields));
    }

    public void testDetect_GivenIncludeWithMissingField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("my_field2", "float")
            .build();

        analyzedFields = new FetchSourceContext(true, new String[]{"your_field1", "my*"}, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No field [your_field1] could be detected"));
    }

    public void testDetect_GivenExcludeAllValidFields() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("my_field2", "float")
            .build();

        analyzedFields = new FetchSourceContext(true, new String[0], new String[]{"my_*"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);
        assertThat(e.getMessage(), equalTo("No compatible fields could be detected in index [source_index]. " +
            "Supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]."));
    }

    public void testDetect_GivenInclusionsAndExclusions() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("my_field1_nope", "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .build();

        analyzedFields = new FetchSourceContext(true, new String[]{"your*", "my_*"}, new String[]{"*nope"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(Arrays.asList("my_field1", "your_field2")));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("my_field1", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.excluded("my_field1_nope", Collections.singleton("float"), "field in excludes list"),
            FieldSelection.included("your_field2", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenIncludedFieldHasUnsupportedType() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("my_field1_nope", "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .addAggregatableField("your_keyword", "keyword")
            .build();

        analyzedFields = new FetchSourceContext(true, new String[]{"your*", "my_*"}, new String[]{"*nope"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("field [your_keyword] has unsupported type [keyword]. " +
            "Supported types are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]."));
    }

    public void testDetect_GivenIndexContainsResultsField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField(RESULTS_FIELD, "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .addAggregatableField("your_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("A field that matches the dest.results_field [ml] already exists; " +
            "please set a different results_field"));
    }

    public void testDetect_GivenIndexContainsResultsFieldAndTaskIsRestarting() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField(RESULTS_FIELD + ".outlier_score", "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .addAggregatableField("your_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), true, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(Arrays.asList("my_field1", "your_field2")));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("my_field1", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("your_field2", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.excluded("your_keyword", Collections.singleton("keyword"), "unsupported type; supported types " +
                "are [boolean, byte, double, float, half_float, integer, long, scaled_float, short]")
        );
    }

    public void testDetect_GivenIncludedResultsField() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField(RESULTS_FIELD, "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .addAggregatableField("your_keyword", "keyword")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]{RESULTS_FIELD}, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("A field that matches the dest.results_field [ml] already exists; " +
            "please set a different results_field"));
    }

    public void testDetect_GivenIncludedResultsFieldAndTaskIsRestarting() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField(RESULTS_FIELD + ".outlier_score", "float")
            .addAggregatableField("my_field1", "float")
            .addAggregatableField("your_field2", "float")
            .addAggregatableField("your_keyword", "keyword")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[]{RESULTS_FIELD}, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), true, 100, fieldCapabilities, Collections.emptyMap());
        ElasticsearchStatusException e = expectThrows(ElasticsearchStatusException.class, extractedFieldsDetector::detect);

        assertThat(e.getMessage(), equalTo("No field [ml] could be detected"));
    }

    public void testDetect_GivenLessFieldsThanDocValuesLimit() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "float")
            .addAggregatableField("field_2", "float")
            .addAggregatableField("field_3", "float")
            .addAggregatableField("a_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), true, 4, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(Arrays.asList("field_1", "field_2", "field_3")));
        assertThat(fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getMethod).collect(Collectors.toSet()),
            contains(equalTo(ExtractedField.Method.DOC_VALUE)));
    }

    public void testDetect_GivenEqualFieldsToDocValuesLimit() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "float")
            .addAggregatableField("field_2", "float")
            .addAggregatableField("field_3", "float")
            .addAggregatableField("a_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), true, 3, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(Arrays.asList("field_1", "field_2", "field_3")));
        assertThat(fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getMethod).collect(Collectors.toSet()),
            contains(equalTo(ExtractedField.Method.DOC_VALUE)));
    }

    public void testDetect_GivenMoreFieldsThanDocValuesLimit() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "float")
            .addAggregatableField("field_2", "float")
            .addAggregatableField("field_3", "float")
            .addAggregatableField("a_keyword", "keyword")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), true, 2, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, equalTo(Arrays.asList("field_1", "field_2", "field_3")));
        assertThat(fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getMethod).collect(Collectors.toSet()),
            contains(equalTo(ExtractedField.Method.SOURCE)));
    }

    private void testDetect_GivenBooleanField(DataFrameAnalyticsConfig config, boolean isRequired, FieldSelection.FeatureType featureType) {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("some_boolean", "boolean")
            .addAggregatableField("some_integer", "integer")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, config, false, 100, fieldCapabilities, config.getAnalysis().getFieldCardinalityLimits());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(2));
        ExtractedField booleanField = allFields.get(0);
        assertThat(booleanField.getTypes(), contains("boolean"));
        assertThat(booleanField.getMethod(), equalTo(ExtractedField.Method.DOC_VALUE));

        assertFieldSelectionContains(fieldExtraction.v2().subList(0, 1),
            FieldSelection.included("some_boolean", Collections.singleton("boolean"), isRequired, featureType)
        );

        SearchHit hit = new SearchHitBuilder(42).addField("some_boolean", true).build();
        assertThat(booleanField.value(hit), arrayContaining(1));

        hit = new SearchHitBuilder(42).addField("some_boolean", false).build();
        assertThat(booleanField.value(hit), arrayContaining(0));

        hit = new SearchHitBuilder(42).addField("some_boolean", Arrays.asList(false, true, false)).build();
        assertThat(booleanField.value(hit), arrayContaining(0, 1, 0));
    }

    public void testDetect_GivenBooleanField_OutlierDetection() {
        // some_boolean is a non-required, numerical feature in outlier detection analysis
        testDetect_GivenBooleanField(buildOutlierDetectionConfig(), false, FieldSelection.FeatureType.NUMERICAL);
    }

    public void testDetect_GivenBooleanField_Regression() {
        // some_boolean is a non-required, numerical feature in regression analysis
        testDetect_GivenBooleanField(buildRegressionConfig("some_integer"), false, FieldSelection.FeatureType.NUMERICAL);
    }

    public void testDetect_GivenBooleanField_Classification_BooleanIsFeature() {
        // some_boolean is a non-required, numerical feature in classification analysis
        testDetect_GivenBooleanField(buildClassificationConfig("some_integer"), false, FieldSelection.FeatureType.NUMERICAL);
    }

    public void testDetect_GivenBooleanField_Classification_BooleanIsDependentVariable() {
        // some_boolean is a required, categorical dependent variable in classification analysis
        testDetect_GivenBooleanField(buildClassificationConfig("some_boolean"), true, FieldSelection.FeatureType.CATEGORICAL);
    }

    public void testDetect_GivenMultiFields() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("a_float", "float")
            .addNonAggregatableField("text_without_keyword", "text")
            .addNonAggregatableField("text_1", "text")
            .addAggregatableField("text_1.keyword", "keyword")
            .addNonAggregatableField("text_2", "text")
            .addAggregatableField("text_2.keyword", "keyword")
            .addAggregatableField("keyword_1", "keyword")
            .addNonAggregatableField("keyword_1.text", "text")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("a_float"), true, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(5));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("a_float", "keyword_1", "text_1.keyword", "text_2.keyword", "text_without_keyword"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("a_float", Collections.singleton("float"), true, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("keyword_1", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("keyword_1.text", Collections.singleton("text"),
                "[keyword_1] is preferred because it is aggregatable"),
            FieldSelection.excluded("text_1", Collections.singleton("text"),
                "[text_1.keyword] is preferred because it is aggregatable"),
            FieldSelection.included("text_1.keyword", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("text_2", Collections.singleton("text"),
                "[text_2.keyword] is preferred because it is aggregatable"),
            FieldSelection.included("text_2.keyword", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.included("text_without_keyword", Collections.singleton("text"), false, FieldSelection.FeatureType.CATEGORICAL)
        );
    }

    public void testDetect_GivenMultiFieldAndParentIsRequired() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "keyword")
            .addAggregatableField("field_1.keyword", "keyword")
            .addAggregatableField("field_2", "float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildClassificationConfig("field_1"), true, 100, fieldCapabilities, Collections.singletonMap("field_1", 2L));
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_1", Collections.singleton("keyword"), true, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.keyword", Collections.singleton("keyword"),
                "[field_1] is required instead"),
            FieldSelection.included("field_2", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenMultiFieldAndMultiFieldIsRequired() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "keyword")
            .addAggregatableField("field_1.keyword", "keyword")
            .addAggregatableField("field_2", "float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildClassificationConfig("field_1.keyword"), true, 100, fieldCapabilities,
            Collections.singletonMap("field_1.keyword", 2L));
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1.keyword", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.excluded("field_1", Collections.singleton("keyword"),
                "[field_1.keyword] is required instead"),
            FieldSelection.included("field_1.keyword", Collections.singleton("keyword"), true, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.included("field_2", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenSeveralMultiFields_ShouldPickFirstSorted() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addNonAggregatableField("field_1", "text")
            .addAggregatableField("field_1.keyword_3", "keyword")
            .addAggregatableField("field_1.keyword_2", "keyword")
            .addAggregatableField("field_1.keyword_1", "keyword")
            .addAggregatableField("field_2", "float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("field_2"), true, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1.keyword_1", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.excluded("field_1", Collections.singleton("text"),
                "[field_1.keyword_1] is preferred because it is aggregatable"),
            FieldSelection.included("field_1.keyword_1", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.keyword_2", Collections.singleton("keyword"), "[field_1.keyword_1] came first"),
            FieldSelection.excluded("field_1.keyword_3", Collections.singleton("keyword"), "[field_1.keyword_1] came first"),
            FieldSelection.included("field_2", Collections.singleton("float"), true, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenMultiFields_OverDocValueLimit() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addNonAggregatableField("field_1", "text")
            .addAggregatableField("field_1.keyword_1", "keyword")
            .addAggregatableField("field_2", "float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("field_2"), true, 0, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_1", Collections.singleton("text"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.keyword_1", Collections.singleton("keyword"),
                "[field_1] is preferred because it supports fetching from source"),
            FieldSelection.included("field_2", Collections.singleton("float"), true, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenParentAndMultiFieldBothAggregatable() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_1", "keyword")
            .addAggregatableField("field_1.keyword", "keyword")
            .addAggregatableField("field_2.keyword", "float")
            .addAggregatableField("field_2.double", "double")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("field_2.double"), true, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1", "field_2.double"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_1", Collections.singleton("keyword"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.keyword", Collections.singleton("keyword"),
                "[field_1] is preferred because it is aggregatable"),
            FieldSelection.included("field_2.double", Collections.singleton("double"), true, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.excluded("field_2.keyword", Collections.singleton("float"), "[field_2.double] is required instead")
        );
    }

    public void testDetect_GivenParentAndMultiFieldNoneAggregatable() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addNonAggregatableField("field_1", "text")
            .addNonAggregatableField("field_1.text", "text")
            .addAggregatableField("field_2", "float")
            .build();

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("field_2"), true, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_1", Collections.singleton("text"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.text", Collections.singleton("text"),
                "[field_1] is preferred because none of the multi-fields are aggregatable"),
            FieldSelection.included("field_2", Collections.singleton("float"), true, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenMultiFields_AndExplicitlyIncludedFields() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addNonAggregatableField("field_1", "text")
            .addAggregatableField("field_1.keyword", "keyword")
            .addAggregatableField("field_2", "float")
            .build();
        analyzedFields = new FetchSourceContext(true, new String[] { "field_1", "field_2" }, new String[0]);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildRegressionConfig("field_2"), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        assertThat(fieldExtraction.v1().getAllFields(), hasSize(2));
        List<String> extractedFieldNames = fieldExtraction.v1().getAllFields().stream().map(ExtractedField::getName)
            .collect(Collectors.toList());
        assertThat(extractedFieldNames, contains("field_1", "field_2"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_1", Collections.singleton("text"), false, FieldSelection.FeatureType.CATEGORICAL),
            FieldSelection.excluded("field_1.keyword", Collections.singleton("keyword"), "field not in includes list"),
            FieldSelection.included("field_2", Collections.singleton("float"), true, FieldSelection.FeatureType.NUMERICAL)
        );
    }

    public void testDetect_GivenSourceFilteringWithIncludes() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_11", "float")
            .addAggregatableField("field_12", "float")
            .addAggregatableField("field_21", "float")
            .addAggregatableField("field_22", "float").build();

        sourceFiltering = new FetchSourceContext(true, new String[] {"field_1*"}, null);

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(2));
        assertThat(allFields.get(0).getName(), equalTo("field_11"));
        assertThat(allFields.get(1).getName(), equalTo("field_12"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_11", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("field_12", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL));
    }

    public void testDetect_GivenSourceFilteringWithExcludes() {
        FieldCapabilitiesResponse fieldCapabilities = new MockFieldCapsResponseBuilder()
            .addAggregatableField("field_11", "float")
            .addAggregatableField("field_12", "float")
            .addAggregatableField("field_21", "float")
            .addAggregatableField("field_22", "float").build();

        sourceFiltering = new FetchSourceContext(true, null, new String[] {"field_1*"});

        ExtractedFieldsDetector extractedFieldsDetector = new ExtractedFieldsDetector(
            SOURCE_INDEX, buildOutlierDetectionConfig(), false, 100, fieldCapabilities, Collections.emptyMap());
        Tuple<ExtractedFields, List<FieldSelection>> fieldExtraction = extractedFieldsDetector.detect();

        List<ExtractedField> allFields = fieldExtraction.v1().getAllFields();
        assertThat(allFields, hasSize(2));
        assertThat(allFields.get(0).getName(), equalTo("field_21"));
        assertThat(allFields.get(1).getName(), equalTo("field_22"));

        assertFieldSelectionContains(fieldExtraction.v2(),
            FieldSelection.included("field_21", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL),
            FieldSelection.included("field_22", Collections.singleton("float"), false, FieldSelection.FeatureType.NUMERICAL));
    }

    private DataFrameAnalyticsConfig buildOutlierDetectionConfig() {
        return new DataFrameAnalyticsConfig.Builder()
            .setId("foo")
            .setSource(new DataFrameAnalyticsSource(SOURCE_INDEX, null, sourceFiltering))
            .setDest(new DataFrameAnalyticsDest(DEST_INDEX, RESULTS_FIELD))
            .setAnalyzedFields(analyzedFields)
            .setAnalysis(new OutlierDetection.Builder().build())
            .build();
    }

    private DataFrameAnalyticsConfig buildRegressionConfig(String dependentVariable) {
        return new DataFrameAnalyticsConfig.Builder()
            .setId("foo")
            .setSource(new DataFrameAnalyticsSource(SOURCE_INDEX, null, sourceFiltering))
            .setDest(new DataFrameAnalyticsDest(DEST_INDEX, RESULTS_FIELD))
            .setAnalyzedFields(analyzedFields)
            .setAnalysis(new Regression(dependentVariable))
            .build();
    }

    private DataFrameAnalyticsConfig buildClassificationConfig(String dependentVariable) {
        return new DataFrameAnalyticsConfig.Builder()
            .setId("foo")
            .setSource(new DataFrameAnalyticsSource(SOURCE_INDEX, null, sourceFiltering))
            .setDest(new DataFrameAnalyticsDest(DEST_INDEX, RESULTS_FIELD))
            .setAnalysis(new Classification(dependentVariable))
            .build();
    }

    /**
     * We assert each field individually to get useful error messages in case of failure
     */
    private static void assertFieldSelectionContains(List<FieldSelection> actual, FieldSelection... expected) {
        assertThat(actual, hasSize(expected.length));
        for (int i = 0; i < expected.length; i++) {
            assertThat("i = " + i, actual.get(i).getName(), equalTo(expected[i].getName()));
            assertThat("i = " + i, actual.get(i).getMappingTypes(), equalTo(expected[i].getMappingTypes()));
            assertThat("i = " + i, actual.get(i).isIncluded(), equalTo(expected[i].isIncluded()));
            assertThat("i = " + i, actual.get(i).isRequired(), equalTo(expected[i].isRequired()));
            assertThat("i = " + i, actual.get(i).getFeatureType(), equalTo(expected[i].getFeatureType()));
            assertThat("i = " + i, actual.get(i).getReason(), equalTo(expected[i].getReason()));
        }
    }

    private static class MockFieldCapsResponseBuilder {

        private final Map<String, Map<String, FieldCapabilities>> fieldCaps = new HashMap<>();

        private MockFieldCapsResponseBuilder addAggregatableField(String field, String... types) {
            return addField(field, true, types);
        }

        private MockFieldCapsResponseBuilder addNonAggregatableField(String field, String... types) {
            return addField(field, false, types);
        }

        private MockFieldCapsResponseBuilder addField(String field, boolean isAggregatable, String... types) {
            Map<String, FieldCapabilities> caps = new HashMap<>();
            for (String type : types) {
                caps.put(type, new FieldCapabilities(field, type, true, isAggregatable, Collections.emptyMap()));
            }
            fieldCaps.put(field, caps);
            return this;
        }

        private FieldCapabilitiesResponse build() {
            FieldCapabilitiesResponse response = mock(FieldCapabilitiesResponse.class);
            when(response.get()).thenReturn(fieldCaps);

            for (String field : fieldCaps.keySet()) {
                when(response.getField(field)).thenReturn(fieldCaps.get(field));
            }
            return response;
        }
    }
}
