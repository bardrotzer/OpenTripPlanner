package org.opentripplanner.model.plan;


import org.opentripplanner.routing.core.Fare;

import java.util.Calendar;
import java.util.List;

/**
 * An Itinerary is one complete way of getting from the start location to the end location.
 */
public class Itinerary {

    /** Total duration of the itinerary in seconds */
    public final long durationSeconds;

    /**
     * How much time is spent on transit, in seconds.
     */
    public final long transitTimeSeconds;

    /**
     * The number of transfers this trip has.
     */
    public final int nTransfers;

    /**
     * How much time is spent waiting for transit to arrive, in seconds.
     */
    public final long waitingTimeSeconds;

    /**
     * How much time is spent walking/biking/driving, in seconds.
     */
    public long nonTransitTimeSeconds = 0;

    /**
     * How far the user has to walk, bike and/or drive, in meters.
     */
    public final double nonTransitDistanceMeters;

    /**
     * Indicates that the walk/bike/drive limit distance has been exceeded for this itinerary.
     */
    public boolean nonTransitLimitExceeded = false;

    /**
     * How much elevation is lost, in total, over the course of the trip, in meters. As an example,
     * a trip that went from the top of Mount Everest straight down to sea level, then back up K2,
     * then back down again would have an elevationLost of Everest + K2.
     */

    public Double elevationLost = 0.0;
    /**
     * How much elevation is gained, in total, over the course of the trip, in meters. See
     * elevationLost.
     */

    public Double elevationGained = 0.0;

    /**
     * If a generalized cost is used in the routing algorithm, this should be the total
     * cost computed by the algorithm. This is relevant for anyone who want to debug an search
     * and tuning the system. The unit should be equivalent to the cost of "one second of transit".
     */
    public int generalizedCost = 0;

    /**
     * This itinerary has a greater slope than the user requested (but there are no possible
     * itineraries with a good slope).
     */
    public boolean tooSloped = false;

     /** TRUE if mode is WALK from start ot end (all legs are walking). */
    public final boolean walkOnly;

    /**
     * The cost of this trip
     */
    public Fare fare = new Fare();

    /**
     * A list of Legs. Each Leg is either a walking (cycling, car) portion of the trip, or a transit
     * trip on a particular vehicle. So a trip where the use walks to the Q train, transfers to the
     * 6, then walks to their destination, has four legs.
     */
    public final List<Leg> legs;


    public Itinerary(List<Leg> legs) {
        if(legs.isEmpty()) { throw new IllegalArgumentException("At least one leg is required."); }

        this.legs = List.copyOf(legs);

        // Set aggregated data
        ItinerariesCalculateLegTotals totals = new ItinerariesCalculateLegTotals(legs);
        this.durationSeconds = totals.totalDurationSeconds;
        this.nTransfers = totals.transfers();
        this.transitTimeSeconds = totals.transitTimeSeconds;
        this.nonTransitTimeSeconds = totals.nonTransitTimeSeconds;
        this.nonTransitDistanceMeters = totals.nonTransitDistanceMeters;
        this.waitingTimeSeconds = totals.waitingTimeSeconds;
        this.walkOnly = totals.walkOnly;
    }

    /**
     * Time that the trip departs.
     */
    public Calendar startTime() {
        return firstLeg().startTime;
    }

    /**
     * Time that the trip arrives.
     */
    public Calendar endTime() {
        return lastLeg().endTime;
    }

    /**
     * Return {@code true} if all legs are WALKING.
     */
    public boolean isWalkingAllTheWay() {
        return walkOnly;
    }

    /** TRUE if alt least one leg is a transit leg. */
    public boolean hasTransit() {
        return transitTimeSeconds > 0;
    }

    public Leg firstLeg() {
        return legs.get(0);
    }

    public Leg lastLeg() {
        return legs.get(legs.size()-1);
    }

    @Override
    public String toString() {
        return "Itinerary{"
                + "nTransfers=" + nTransfers
                + ", durationSeconds=" + durationSeconds
                + ", generalizedCost=" + generalizedCost
                + ", nonTransitTimeSeconds=" + nonTransitTimeSeconds
                + ", transitTimeSeconds=" + transitTimeSeconds
                + ", waitingTimeSeconds=" + waitingTimeSeconds
                + ", nonTransitDistanceMeters=" + nonTransitDistanceMeters
                + ", nonTransitLimitExceeded=" + nonTransitLimitExceeded
                + ", tooSloped=" + tooSloped
                + ", elevationLost=" + elevationLost
                + ", elevationGained=" + elevationGained
                + ", legs=" + legs
                + ", fare=" + fare
                + '}';
    }
}
