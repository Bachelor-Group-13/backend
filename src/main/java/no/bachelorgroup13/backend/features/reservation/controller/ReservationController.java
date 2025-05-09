package no.bachelorgroup13.backend.features.reservation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.bachelorgroup13.backend.features.push.repository.PushSubscriptionRepository;
import no.bachelorgroup13.backend.features.push.service.PushServiceWrapper;
import no.bachelorgroup13.backend.features.reservation.dto.ReservationDto;
import no.bachelorgroup13.backend.features.reservation.entity.Reservation;
import no.bachelorgroup13.backend.features.reservation.mapper.ReservationMapper;
import no.bachelorgroup13.backend.features.reservation.service.ReservationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservation", description = "Endpoints for managing reservations.")
public class ReservationController {

    private final ReservationService reservationService;
    private final PushSubscriptionRepository pushRepository;
    private final PushServiceWrapper pushService;
    private final ReservationMapper reservationMapper;

    @Operation(summary = "Get all reservations")
    @GetMapping
    public ResponseEntity<List<ReservationDto>> getAllReservations() {
        return ResponseEntity.ok(
                reservationService.getAllReservations().stream()
                        .map(reservationMapper::toDto)
                        .collect((Collectors.toList())));
    }

    @Operation(summary = "Get reservation by ID")
    @GetMapping("/{id}")
    public ResponseEntity<ReservationDto> getReservationById(@PathVariable Integer id) {
        return reservationService
                .getReservationById(id)
                .map(reservationMapper::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get reservations by user ID")
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ReservationDto>> getReservationsByUserId(@PathVariable UUID userId) {
        return ResponseEntity.ok(
                reservationService.getReservationsByUserId(userId).stream()
                        .map(reservationMapper::toDto)
                        .collect(Collectors.toList()));
    }

    @Operation(summary = "Get reservations by date")
    @GetMapping("/date/{date}")
    public ResponseEntity<List<ReservationDto>> getReservationsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(
                reservationService.getReservationsByDate(date).stream()
                        .map(reservationMapper::toDto)
                        .collect(Collectors.toList()));
    }

    @Operation(summary = "Get reservations by license plate")
    @GetMapping("/license-plate/{licensePlate}")
    public ResponseEntity<List<ReservationDto>> getReservationsByLicensePlate(
            @PathVariable String licensePlate) {
        return ResponseEntity.ok(
                reservationService.getReservationsByLicensePlate(licensePlate).stream()
                        .map(reservationMapper::toDto)
                        .collect(Collectors.toList()));
    }

    @Operation(summary = "Get reservations by spot number")
    @PostMapping
    public ResponseEntity<?> createReservation(@RequestBody ReservationDto reservationDto) {
        log.info("Received reservation request: {}", reservationDto);

        try {
            // Validate non-anonymous reservations
            if (!reservationDto.isAnonymous()) {
                if (reservationDto.getUserId() == null) {
                    return ResponseEntity.badRequest()
                            .body("User ID is required for non-anonymous reservations");
                }

                if (reservationService.hasActiveReservation(reservationDto.getUserId())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body("User already has an active reservation");
                }
            }

            // Validate required fields for all reservations
            if (reservationDto.getSpotNumber() == null
                    || reservationDto.getReservationDate() == null) {
                return ResponseEntity.badRequest()
                        .body("Spot number and reservation date are required");
            }

            // Create reservation
            Reservation reservation = reservationMapper.toEntity(reservationDto);
            Reservation saved = reservationService.createReservation(reservation);
            ReservationDto savedDto = reservationMapper.toDto(saved);

            // Send push notification for non-anonymous reservations
            if (!reservationDto.isAnonymous() && reservationDto.getUserId() != null) {
                try {
                    sendPushNotification(saved);
                } catch (Exception e) {
                    log.error("Failed to send push notification", e);
                }
            }

            log.info("Successfully created reservation: {}", saved);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedDto);

        } catch (IllegalArgumentException e) {
            log.error("Invalid reservation request", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Conflict while creating reservation", e);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while creating reservation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create reservation: " + e.getMessage());
        }
    }

    @Operation(summary = "Send push notification")
    private void sendPushNotification(Reservation reservation) {
        pushRepository
                .findAllByUserId(reservation.getUserId())
                .forEach(
                        sub -> {
                            try {
                                pushService.sendPush(
                                        sub,
                                        "Spot " + reservation.getSpotNumber() + " reserved!",
                                        "Check if you parked in someone");
                            } catch (IOException | GeneralSecurityException e) {
                                log.error("Failed to send push notification to subscription", e);
                            }
                        });
    }

    @Operation(summary = "Update reservation")
    @PutMapping("/{id}")
    public ResponseEntity<ReservationDto> updateReservation(
            @PathVariable Integer id, @RequestBody ReservationDto reservationDto) {
        return reservationService
                .getReservationById(id)
                .map(
                        existingReservation -> {
                            Reservation reservation = reservationMapper.toEntity(reservationDto);
                            reservation.setId(id);
                            Reservation updated = reservationService.updateReservation(reservation);
                            return ResponseEntity.ok(reservationMapper.toDto(updated));
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete reservation")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReservation(@PathVariable Integer id) {
        return reservationService
                .getReservationById(id)
                .map(
                        reservation -> {
                            reservationService.deleteReservation(id);
                            return ResponseEntity.noContent().<Void>build();
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete all reservations")
    @DeleteMapping("/all")
    @PreAuthorize("hasRole('ROLE_DEVELOPER')")
    public ResponseEntity<Void> deleteAllReservations() {
        reservationService.deleteAllReservations();
        return ResponseEntity.noContent().build();
    }
}
