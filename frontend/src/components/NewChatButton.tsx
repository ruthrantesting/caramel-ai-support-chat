interface Props {
  onClick: () => void;
}

/** Starts a fresh conversation. Works while a reply is streaming: that reply is stopped. */
export function NewChatButton({ onClick }: Props) {
  return (
    <button type="button" className="new-chat" onClick={onClick}>
      New chat
    </button>
  );
}
