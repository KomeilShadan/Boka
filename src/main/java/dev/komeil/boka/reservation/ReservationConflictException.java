package dev.komeil.boka.reservation;

public class ReservationConflictException extends RuntimeException {
  public ReservationConflictException(String message) {
    super(message);
  }
}
