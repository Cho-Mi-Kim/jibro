package org.jibro.preparation;

/** Picker metadata; saved answers, notes and guidance belong to the selected record endpoint. */
public record PreparationListItem(String id, String noticeId, String title, int version, int revision, int progress) {
}
