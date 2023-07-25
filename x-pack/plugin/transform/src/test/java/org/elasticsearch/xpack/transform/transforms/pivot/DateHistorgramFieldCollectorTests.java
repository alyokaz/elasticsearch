/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.transforms.pivot;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchResponseSections;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation.SingleValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.transform.transforms.TransformCheckpoint;
import org.elasticsearch.xpack.core.transform.transforms.pivot.DateHistogramGroupSource;
import org.elasticsearch.xpack.core.transform.transforms.pivot.SingleGroupSource;
import org.elasticsearch.xpack.transform.transforms.Function.ChangeCollector;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DateHistorgramFieldCollectorTests extends ESTestCase {
    Map<String, SingleGroupSource> groups = new LinkedHashMap<>();

    SingleValue minTimestamp = mock(NumericMetricsAggregation.SingleValue.class);
    SingleValue maxTimestamp = mock(NumericMetricsAggregation.SingleValue.class);

    final String TIMESTAMP = "timestamp";
    final String OUTPUT_TIMESTAMP = "output_timestamp";
    final String SYNC_TIMESTAMP = "sync_timestamp";

    SingleGroupSource groupBy = new DateHistogramGroupSource(
        TIMESTAMP,
        null,
        false,
        new DateHistogramGroupSource.FixedInterval(DateHistogramInterval.MINUTE),
        null,
        null
    );

    final double MIN_TIMESTAMP_VALUE = 122_633;
    final double MAX_TIMESTAMP_VALUE = 302_525;
    final double EXPECTED_LOWER_BOUND = 120_000;

    final double EXPECTED_UPPER_BOUND = 360_000;

    @Before
    public void setUpDateHistogramFieldCollectorTests() {
        when(minTimestamp.getName()).thenReturn("_transform_change_collector.output_timestamp.min");
        when(maxTimestamp.getName()).thenReturn("_transform_change_collector.output_timestamp.max");
        when(minTimestamp.value()).thenReturn(MIN_TIMESTAMP_VALUE);
        when(maxTimestamp.value()).thenReturn(MAX_TIMESTAMP_VALUE);
    }

    public void testWhenFieldAndSyncFieldSame() {
        groups.put(OUTPUT_TIMESTAMP, groupBy);
        ChangeCollector collector = CompositeBucketsChangeCollector.buildChangeCollector(groups, TIMESTAMP);
        QueryBuilder queryBuilder = buildFilterQuery(collector);

        assertQuery(queryBuilder, 60_000.0, TIMESTAMP);
    }

    public void testWhenFieldAndSyncFieldDifferent() {
        groups.put(OUTPUT_TIMESTAMP, groupBy);
        ChangeCollector collector = CompositeBucketsChangeCollector.buildChangeCollector(groups, SYNC_TIMESTAMP);

        // simulate the agg response, that should inject
        SearchResponse response = buildSearchResponse(minTimestamp, maxTimestamp);
        collector.processSearchResponse(response);

        // checkpoints are provided although are not used in this case
        QueryBuilder queryBuilder = buildFilterQuery(collector);

        assertQuery(queryBuilder, EXPECTED_LOWER_BOUND, EXPECTED_UPPER_BOUND, TIMESTAMP);
    }

    public void testWhenOutputAndSyncFieldSame() {
        groups.put(OUTPUT_TIMESTAMP, groupBy);
        ChangeCollector collector = CompositeBucketsChangeCollector.buildChangeCollector(groups, SYNC_TIMESTAMP);

        // simulate the agg response, that should inject
        SearchResponse response = buildSearchResponse(minTimestamp, maxTimestamp);
        collector.processSearchResponse(response);
        QueryBuilder queryBuilder = buildFilterQuery(collector);

        assertQuery(queryBuilder, EXPECTED_LOWER_BOUND, EXPECTED_UPPER_BOUND, TIMESTAMP);
    }

    public void testMissingBucketDisablesOptimization() {
        // missing bucket disables optimization
        DateHistogramGroupSource groupBy = new DateHistogramGroupSource(
            TIMESTAMP,
            null,
            true,
            new DateHistogramGroupSource.FixedInterval(DateHistogramInterval.MINUTE),
            null,
            null
        );
        groups.put(OUTPUT_TIMESTAMP, groupBy);

        // field and sync_field are the same
        ChangeCollector collector = CompositeBucketsChangeCollector.buildChangeCollector(groups, TIMESTAMP);
        QueryBuilder queryBuilder = buildFilterQuery(collector);

        assertNull(queryBuilder);

        // field and sync_field are different
        collector = CompositeBucketsChangeCollector.buildChangeCollector(groups, SYNC_TIMESTAMP);
        queryBuilder = buildFilterQuery(collector);

        assertNull(queryBuilder);
    }

    private void assertQuery(QueryBuilder queryBuilder, Double expectedLowerBound, Double expectedUpperBound, String expectedFieldName) {
        assertQuery(queryBuilder, expectedLowerBound, expectedFieldName);

        // the upper bound is not rounded
        assertThat(((RangeQueryBuilder) queryBuilder).to(), equalTo(expectedUpperBound.longValue()));
        assertTrue(((RangeQueryBuilder) queryBuilder).includeUpper());
    }

    private void assertQuery(QueryBuilder queryBuilder, Double expectedLowerBound, String expectedFieldName) {
        assertNotNull(queryBuilder);
        assertThat(queryBuilder, instanceOf(RangeQueryBuilder.class));

        // lower bound is rounded down to the nearest time unit
        assertThat(((RangeQueryBuilder) queryBuilder).from(), equalTo(expectedLowerBound.longValue()));
        assertTrue(((RangeQueryBuilder) queryBuilder).includeLower());

        assertThat(((RangeQueryBuilder) queryBuilder).fieldName(), equalTo(expectedFieldName));
    }

    // Util methods
    private QueryBuilder buildFilterQuery(ChangeCollector collector) {
        return collector.buildFilterQuery(
            new TransformCheckpoint("t_id", 42L, 42L, Collections.emptyMap(), 66_666L),
            new TransformCheckpoint("t_id", 42L, 42L, Collections.emptyMap(), 200_222L)
        );
    }

    private SearchResponse buildSearchResponse(SingleValue minTimestamp, SingleValue maxTimestamp) {
        SearchResponseSections sections = new SearchResponseSections(null, new Aggregations(Arrays.asList(minTimestamp, maxTimestamp)),
            null, false, null, null, 1);
        return new SearchResponse(sections, null, 1, 1, 0, 0,
            ShardSearchFailure.EMPTY_ARRAY, null);
    }


}
