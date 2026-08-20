package org.neo4j.agentmemory.e2e.assertions;

import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.AbstractAssert;
import org.neo4j.agentmemory.Conversation;

public final class ConversationAssert extends AbstractAssert<ConversationAssert, Conversation> {
    private ConversationAssert(Conversation actual) {
        super(actual, ConversationAssert.class);
    }

    public static ConversationAssert assertThat(Conversation actual) {
        return new ConversationAssert(actual);
    }

    public ConversationAssert hasId() {
        isNotNull();
        if (actual.id() == null) {
            failWithMessage("Expected conversation to have an id");
        }
        return this;
    }

    public ConversationAssert hasId(UUID expected) {
        isNotNull();
        if (!Objects.equals(actual.id(), expected)) {
            failWithMessage("Expected conversation id <%s> but was <%s>", expected, actual.id());
        }
        return this;
    }

    public ConversationAssert hasUserId(String expected) {
        isNotNull();
        if (!Objects.equals(actual.userId(), expected)) {
            failWithMessage("Expected conversation user id <%s> but was <%s>", expected, actual.userId());
        }
        return this;
    }
}
