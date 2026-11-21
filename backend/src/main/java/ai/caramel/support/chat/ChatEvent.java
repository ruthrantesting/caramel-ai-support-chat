package ai.caramel.support.chat;

/**
 * One event in a streamed reply. The controller maps these to SSE events named {@code token},
 * {@code done}, and {@code error} (contracts/sse-events.md).
 */
public sealed interface ChatEvent {

    /** A chunk of assistant text, in arrival order. */
    record Token(String text) implements ChatEvent {}

    /** The reply completed normally. */
    record Done() implements ChatEvent {}

    /** The reply ended abnormally. No {@link Done} follows. */
    record Failed(ChatFailure failure) implements ChatEvent {}
}
