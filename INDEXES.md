# Airline Booking Database Index Documentation

The following indexes were added to optimize common query patterns identified in airline booking systems:

## 1. Booking Lookups Index
**Pattern:** Lookups by booking reference code
**Index:** `idx_bookings_reference` on `bookings(booking_reference)`  
**Benefit:** Accelerates checkout flows and booking modification operations

## 2. User Booking History Index
**Pattern:** Retrieving a user's booking history in reverse chronological order
**Index:** Composite index `idx_bookings_user_date` on `bookings(user_id, booking_date DESC)`  
**Benefit:** Optimizes display of booking history in user accounts

## 3. Seat Availability Index
**Pattern:** Finding available seats for a particular flight and class
**Index:** `idx_seats_flight_availability` on `seats(flight_id, class, is_available)` with `seat_number` included  
**Benefit:** Drastically speeds up seat selection during booking

## 4. Flight Search Index
**Pattern:** Finding flights by route/date combination
**Index:** Composite index `idx_flights_route_departure` on `flights(departure_airport, arrival_airport, scheduled_departure)`  
**Benefit:** Optimizes common flight search queries

## 5. Passenger Name Lookup
**Pattern:** Searching passengers by last name
**Index:** `idx_passengers_last_name` on `passengers(last_name)`  
**Benefit:** Accelerates passenger lookup for admin operations

## 6. Booking-Passenger Relationship
**Pattern:** Retrieving passengers for a specific booking
**Index:** `idx_passengers_booking_id` on `passengers(booking_id)`  
**Benefit:** Optimizes boarding pass generation and manifest queries