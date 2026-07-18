package com.gatto.funds.event;

import java.util.UUID;

/**
 * What a consumer receives. In the real system this is a Kafka record; here it is the shape
 * the relay hands to in-process consumers. eventId is what the consumer dedups on.
 */
public record EventEnvelope(UUID eventId, String type, String payload) {
}
