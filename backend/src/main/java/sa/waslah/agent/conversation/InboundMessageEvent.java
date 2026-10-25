package sa.waslah.agent.conversation;

import java.util.UUID;

/**
 * Published after a new customer message is committed and the agent is allowed to answer it
 * (the conversation is not paused by a human). Phase 2's agent listens for this.
 */
public record InboundMessageEvent(UUID officeId, UUID conversationId, UUID messageId) {
}
