package com.portal.dto;

/**
 * One To Do task reachable from a data row, reduced to what the Views grid needs: where to
 * navigate ({@code taskId}) and what to call it when the row has more than one
 * ({@code taskName}, the BPMN node name). Deliberately not a {@link TaskInfo} — the grid marker
 * must not become a second, drifting copy of the To Do payload.
 */
public record MyTaskRef(String taskId, String taskName) {
}
