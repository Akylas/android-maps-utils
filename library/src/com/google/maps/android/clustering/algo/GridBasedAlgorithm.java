/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.maps.android.clustering.algo;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import android.support.v4.util.LongSparseArray;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.geometry.Point;
import com.google.maps.android.projection.SphericalMercatorProjection;

/**
 * Groups markers into a grid.
 */
public class GridBasedAlgorithm<T extends ClusterItem> implements Algorithm<T> {
    private int gridSize = 100;

    private final Set<T> mItems = Collections.synchronizedSet(new HashSet<T>());

    @Override
    public void addItem(T item) {
        mItems.add(item);
    }

    @Override
    public void addItems(Collection<T> items) {
        mItems.addAll(items);
    }

    @Override
    public void clearItems() {
        mItems.clear();
    }

    @Override
    public void removeItem(T item) {
        mItems.remove(item);
    }
    
    protected StaticCluster<T> createCluster() {
            return new StaticCluster<T>();
    }

    @Override
    public Set<? extends Cluster<T>> getClusters(double zoom, LatLngBounds visibleBounds) {
        if (mItems.size() == 0) {
            return null;
        }
        double minZoom = getMinZoom();
        double maxZoom = getMaxZoom();
        if ((minZoom >= 0 && zoom < minZoom) || (maxZoom >= 0 && zoom > maxZoom)) {
            return null;
        }
        long numCells = (long) Math.ceil(256 * Math.pow(2, zoom) / gridSize);
        SphericalMercatorProjection proj = new SphericalMercatorProjection(numCells);

        HashSet<Cluster<T>> clusters = new HashSet<Cluster<T>>();
        LongSparseArray<StaticCluster<T>> sparseArray = new LongSparseArray<StaticCluster<T>>();

        synchronized (mItems) {
            for (T item : mItems) {
                LatLng pos = item.getPosition();
//                if (!visibleBounds.contains(pos) || !item.isVisible()) {
                if (!item.isVisible()) {
                    continue;
                }
                
                if (!item.canBeClustered()) {
                    StaticCluster<T> cluster = createCluster();
                    cluster.add(item);
                    clusters.add(cluster);
                    continue;
                }
                Point p = proj.toPoint(pos);

                long coord = getCoord(numCells, p.x, p.y);

                StaticCluster<T> cluster = sparseArray.get(coord);
                if (cluster == null) {
                    cluster = createCluster();
                    sparseArray.put(coord, cluster);
                    clusters.add(cluster);
                }
                cluster.add(item);
            }
        }
        
        for (Cluster<T> cluster: clusters) {
            if (cluster instanceof StaticCluster) {
                ((StaticCluster<T>) cluster).update();
            }
        }

        return clusters;
    }

    @Override
    public Collection<T> getItems() {
        return mItems;
    }

    private static long getCoord(long numCells, double x, double y) {
        return (long) (numCells * Math.floor(x) + Math.floor(y));
    }
    
    public void setGridSize(int size) {
        gridSize = size;
    }

    @Override
    public void removeItemsNotInRectangle(LatLngBounds bounds) {
        synchronized (mItems) {
            Set<T> toRemove = Collections.synchronizedSet(new HashSet<T>());
            for (T item : mItems) {
                if (!bounds.contains(item.getPosition())) {
                    toRemove.add(item);
                }
            }
            mItems.removeAll(toRemove);
        }
    }

    @Override
    public void removeItems(Collection<T> items) {
        synchronized (mItems) {
           mItems.removeAll(items);
        }
    }
    
    protected double getMinZoom() {
        return -1;
    }

    protected double getMaxZoom() {
        return -1;
    }
}