package com.mathfast.exception;

import java.util.UUID;

/**
 * Thrown when a teacher tries to start a race in a room with no connected human players.
 */
public class EmptyRoomException extends ApiException {
    public EmptyRoomException(UUID roomId) {
        super("Cannot start race: room " + roomId + " has no connected players.", 400);
    }
}
