package org.opentripplanner.gtfs.mapping;

import org.opentripplanner.model.Station;
import org.opentripplanner.util.MapUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.opentripplanner.gtfs.mapping.AgencyAndIdMapper.mapAgencyAndId;

/**
 * Responsible for mapping GTFS Stop into the OTP model.
 *
 * <p>NOTE! This class has state. This class also holds a index of all mapped stops to avoid mapping the
 * same stop twice. We do this because the library (onebusaway) return transfers with Stop object references,
 * not stop ids. Instead of looking up the Stops by id in the {@link TransferMapper} we just use the
 * this class to cache stops. This way, the order of witch stops and transfers are mapped does not matter.
 */
class StationMapper {

    /** @see StationMapper (this class JavaDoc) for way we need this. */
    private Map<org.onebusaway.gtfs.model.Stop, Station> mappedStops = new HashMap<>();

    /** Map from GTFS to OTP model, {@code null} safe.  */
    Station map(org.onebusaway.gtfs.model.Stop orginal) {
        return orginal == null ? null : mappedStops.computeIfAbsent(orginal, this::doMap);
    }

    private Station doMap(org.onebusaway.gtfs.model.Stop rhs) {
        Station otpStation = new Station();

        otpStation.setId(mapAgencyAndId(rhs.getId()));
        otpStation.setName(rhs.getName());
        otpStation.setLat(rhs.getLat());
        otpStation.setLon(rhs.getLon());
        otpStation.setCode(rhs.getCode());
        otpStation.setUrl(rhs.getUrl());
        otpStation.setTimezone(
                rhs.getTimezone() != null
                        ? TimeZone.getTimeZone(rhs.getTimezone())
                        : null);

        return otpStation;
    }
}
