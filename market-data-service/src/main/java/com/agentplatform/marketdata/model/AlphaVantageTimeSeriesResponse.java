package com.agentplatform.marketdata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AlphaVantageTimeSeriesResponse(
    @JsonProperty("Meta Data") MetaData metaData,
    @JsonProperty("Time Series (5min)") Map<String, OhlcvData> timeSeries5min,
    @JsonProperty("Time Series (Daily)") Map<String, OhlcvData> timeSeriesDaily
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetaData(
        @JsonProperty("1. Information") String information,
        @JsonProperty("2. Symbol") String symbol,
        @JsonProperty("3. Last Refreshed") String lastRefreshed
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OhlcvData(
        @JsonProperty("1. open") String open,
        @JsonProperty("2. high") String high,
        @JsonProperty("3. low") String low,
        @JsonProperty("4. close") String close,
        @JsonProperty("5. volume") String volume
    ) {
        public double closeAsDouble() {
            return Double.parseDouble(close);
        }
        public double openAsDouble() {
            return Double.parseDouble(open);
        }
        public double highAsDouble() {
            return Double.parseDouble(high);
        }
        public double lowAsDouble() {
            return Double.parseDouble(low);
        }
        public long volumeAsLong() {
            return Long.parseLong(volume);
        }
    }
}
