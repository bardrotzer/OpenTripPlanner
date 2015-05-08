package org.opentripplanner.graph_builder.linking;

import gnu.trove.map.TIntDoubleMap;
import gnu.trove.map.hash.TIntDoubleHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import jersey.repackaged.com.google.common.collect.Lists;

import com.google.common.collect.Collections2;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.ParkAndRideLinkEdge;
import org.opentripplanner.routing.edgetype.PartialStreetEdge;
import org.opentripplanner.routing.edgetype.StreetBikeParkLink;
import org.opentripplanner.routing.edgetype.StreetBikeRentalLink;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTransitLink;
import org.opentripplanner.routing.edgetype.StreetWithElevationEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.BikeParkVertex;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.routing.vertextype.ParkAndRideVertex;
import org.opentripplanner.routing.vertextype.SplitterVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.updater.bike_park.BikeParkUpdater;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.linearref.LinearLocation;
import com.vividsolutions.jts.linearref.LocationIndexedLine;

/** A class that links transit stops to streets by splitting the streets */
public class SimpleStreetSplitter {
	
	public static final int MAX_SEARCH_RADIUS_METERS = 1000;
	
	/** if there are two ways and the distances to them differ by less than this value, we link to both of them */
	public static final double DUPLICATE_WAY_EPSILON_METERS = 0.001;
		
	private Graph graph;
	
	private HashGridSpatialIndex<StreetEdge> idx;
	
	private static GeometryFactory geometryFactory = GeometryUtils.getGeometryFactory();
	
	/**
	 * Construct a new SimpleStreetSplitter. Be aware that only one SimpleStreetSplitter should be
	 * active on a graph at any given time.
	 * @param graph
	 */
	public SimpleStreetSplitter (Graph graph) {
		this.graph = graph;
		
		// build a nice private spatial index, since we're adding and removing edges
		idx = new HashGridSpatialIndex<StreetEdge>();
		
		for (StreetEdge se : Iterables.filter(graph.getEdges(), StreetEdge.class)) {
			idx.insert(se.getGeometry().getEnvelopeInternal(), se);
		}
	}
	
	public void link () {		
		for (Vertex v : graph.getVertices()) {
			if (v instanceof TransitStop || v instanceof BikeRentalStationVertex || v instanceof ParkAndRideVertex)
				link(v);		
		}
	}
	
	/** Link this vertex into the graph */
	public void link (Vertex vertex) {
		// find nearby street edges
		// TODO: we used to use an expanding-envelope search, which is more efficient in
		// dense areas. but first let's see how inefficient this is. I suspect it's not too
		// bad and the gains in simplicity are considerable.
		final double radiusDeg = SphericalDistanceLibrary.metersToDegrees(MAX_SEARCH_RADIUS_METERS);
		
		Envelope env = new Envelope(vertex.getCoordinate());
		
		// local equirectangular projection
		final double xscale = Math.cos(vertex.getLat() * Math.PI / 180);
		
		env.expandBy(radiusDeg / xscale, radiusDeg);
		
		double duplicateDeg = SphericalDistanceLibrary.metersToDegrees(DUPLICATE_WAY_EPSILON_METERS);
		
		// We sort the list of candidate edges by distance to the stop
		// This should remove any issues with things coming out of the spatial index in different orders
		// Then we link to everything that is within DUPLICATE_WAY_EPSILON_METERS of of the best distance
		// so that we capture back edges and duplicate ways.
		List<StreetEdge> candidateEdges = new ArrayList<StreetEdge>(
				Collections2.filter(idx.query(env), new Predicate<StreetEdge>() {

					@Override
					public boolean apply(StreetEdge edge) {
						// note: not filtering by radius here as distance calculation is expensive
						// we do that below.
						return edge.canTraverse(new TraverseModeSet(TraverseMode.WALK)) &&
								// only link to edges still in the graph.
								edge.getToVertex().getIncoming().contains(edge);
					}
				})
				);
		
		// make a map of distances
		final TIntDoubleMap distances = new TIntDoubleHashMap();
		
		for (StreetEdge e : candidateEdges) {
			distances.put(e.getId(), distance(vertex, e, xscale));
		}
		
		// sort the list
		Collections.sort(candidateEdges, new Comparator<StreetEdge> () {

			@Override
			public int compare(StreetEdge o1, StreetEdge o2) {
				double diff = distances.get(o1.getId()) - distances.get(o2.getId());
				if (diff < 0)
					return -1;
				if (diff > 0)
					return 1;
				return 0;
			}
			
		});
		
		// find the closest candidate edges
		if (candidateEdges.isEmpty() || distances.get(candidateEdges.get(0).getId()) > radiusDeg)
			return;
		
		// find the best edge
		double bestDist = distances.get(candidateEdges.get(0).getId());
		List<StreetEdge> bestEdges = Lists.newArrayList();
		
		// add edges until there is a break of epsilon meters.
		int i = 0;
		do {
			bestEdges.add(candidateEdges.get(i++));
		} while (i < candidateEdges.size() &&
				distances.get(candidateEdges.get(i).getId()) - distances.get(candidateEdges.get(i - 1).getId()) < duplicateDeg);
		
		for (StreetEdge edge : bestEdges) {
			link(vertex, edge, xscale);
		}	
	}
	
	/** split the edge and link in the transit stop */
	private void link (Vertex tstop, StreetEdge edge, double xscale) {
		// TODO: we've already built this line string, we should save it
		LineString orig = edge.getGeometry();
		LineString transformed = equirectangularProject(orig, xscale);
		LocationIndexedLine il = new LocationIndexedLine(transformed);
		LinearLocation ll = il.project(new Coordinate(tstop.getLon() * xscale, tstop.getLat()));
		
		// if we're very close to one end of the line or the other, or endwise, don't bother to split,
		// cut to the chase and link directly
		if (ll.getSegmentIndex() == 0 && ll.getSegmentFraction() < 1e-8) {
			makeLinkEdges(tstop, (StreetVertex) edge.getFromVertex());
		}
		// -1 converts from count to index. Because of the fencepost problem, npoints - 1 is the "segment"
		// past the last point
		else if (ll.getSegmentIndex() == orig.getNumPoints() - 1) {
			makeLinkEdges(tstop, (StreetVertex) edge.getToVertex());
		}
		
		// nPoints - 2: -1 to correct for index vs count, -1 to account for fencepost problem
		else if (ll.getSegmentIndex() == orig.getNumPoints() - 2 && ll.getSegmentFraction() > 1 - 1e-8) {
			makeLinkEdges(tstop, (StreetVertex) edge.getToVertex());
		}
		
		else {	
			// split the edge, get the split vertex
			SplitterVertex v0 = split(edge, ll);
			makeLinkEdges(tstop, v0);
		}
	}
	
	/** Split the street edge at the given fraction */
	private SplitterVertex split (StreetEdge edge, LinearLocation ll) {
		LineString geometry = edge.getGeometry();
		
		// create the geometries
		Coordinate splitPoint = ll.getCoordinate(geometry);
		
		// every edge can be split exactly once, so this is a valid label
		SplitterVertex v = new SplitterVertex(graph, "split from " + edge.getId(), splitPoint.x, splitPoint.y);
		
		// make the edges
		// TODO this is using the StreetEdge implementation of split, which will discard elevation information
		// on edges that have it
		P2<StreetEdge> edges = edge.split(v);
		
		// update indices
		idx.insert(edges.first.getGeometry().getEnvelopeInternal(), edges.first);
		idx.insert(edges.second.getGeometry().getEnvelopeInternal(), edges.second);
		
		// (no need to remove original edge, we filter it when it comes out of the index)
		
		// remove original edge
		edge.getToVertex().removeIncoming(edge);
		edge.getFromVertex().removeOutgoing(edge);
		
		return v;
	}
	
	private void makeLinkEdges (Vertex from, StreetVertex to) {
		if  (from instanceof TransitStop)
			makeTransitLinkEdges((TransitStop) from, to);
		else if (from instanceof BikeRentalStationVertex)
			makeBikeRentalLinkEdges((BikeRentalStationVertex) from, to);
		else if (from instanceof BikeParkVertex)
			makeBikeParkEdges((BikeParkVertex) from, to);
		else if (from instanceof ParkAndRideVertex)
			makeParkAndRideEdges((ParkAndRideVertex) from, to);
	}
	
	private void makeParkAndRideEdges(ParkAndRideVertex from, StreetVertex to) {
		for (ParkAndRideLinkEdge prle : Iterables.filter(from.getOutgoing(), ParkAndRideLinkEdge.class)) {
			if (prle.getToVertex() == to)
				return;
		}
		
		new ParkAndRideLinkEdge(from, to);
		new ParkAndRideLinkEdge(to, from);
	}

	/** Make bike park edges */
	private void makeBikeParkEdges(BikeParkVertex from, StreetVertex to) {
		for (StreetBikeParkLink sbpl : Iterables.filter(from.getOutgoing(), StreetBikeParkLink.class)) {
			if (sbpl.getToVertex() == to)
				return;
		}
		
		new StreetBikeParkLink(from, to);
		new StreetBikeParkLink(to, from);
	}

	/** 
	 * Make link edges, unless they already exist.
	 */
	private void makeTransitLinkEdges (TransitStop tstop, StreetVertex v) {
		// ensure that the requisite edges do not already exist
		// this can happen if we link to duplicate ways that have the same start/end vertices.
		for (StreetTransitLink e : Iterables.filter(tstop.getOutgoing(), StreetTransitLink.class)) {
			if (e.getToVertex() == v)
				return;
		}
		
		new StreetTransitLink(tstop, v, tstop.hasWheelchairEntrance());
		new StreetTransitLink(v, tstop, tstop.hasWheelchairEntrance());
	}
	
	/** Make link edges for bike rental */
	private void makeBikeRentalLinkEdges (BikeRentalStationVertex from, StreetVertex to) {
		for (StreetBikeRentalLink sbrl : Iterables.filter(from.getOutgoing(), StreetBikeRentalLink.class)) {
			if (sbrl.getToVertex() == to)
				return;
		}
		
		new StreetBikeRentalLink(from, to);
		new StreetBikeRentalLink(to, from);
	}
	
	/** projected distance from stop to edge, in latitude degrees */
	private static double distance (Vertex tstop, StreetEdge edge, double xscale) {
		// use JTS internal tools wherever possible
		LineString transformed = equirectangularProject(edge.getGeometry(), xscale);
		return transformed.distance(geometryFactory.createPoint(new Coordinate(tstop.getLon() * xscale, tstop.getLat())));
	}
	
	private static LineString equirectangularProject(LineString geometry, double xscale) {
		Coordinate[] coords = new Coordinate[geometry.getNumPoints()];
		
		for (int i = 0; i < coords.length; i++) {
			Coordinate c = geometry.getCoordinateN(i);
			c = (Coordinate) c.clone();
			c.x *= xscale;
			coords[i] = c;
		}
		
		return geometryFactory.createLineString(coords);
	}
}