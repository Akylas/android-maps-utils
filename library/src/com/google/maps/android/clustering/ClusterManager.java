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

package com.google.maps.android.clustering;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.support.v4.util.Pair;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnCameraChangeListener;
import com.google.android.gms.maps.Projection;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.maps.android.MarkerManager;
import com.google.maps.android.clustering.algo.Algorithm;
import com.google.maps.android.clustering.view.ClusterRenderer;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.util.Log;

/**
 * Groups many items on a map based on zoom level.
 * <p/>
 * ClusterManager should be added to the map as an:
 * <ul>
 * <li>{@link com.google.android.gms.maps.GoogleMap.OnCameraChangeListener}</li>
 * <li>{@link com.google.android.gms.maps.GoogleMap.OnMarkerClickListener}</li>
 * </ul>
 */
public class ClusterManager<T extends ClusterItem>
        implements GoogleMap.OnCameraChangeListener,
        GoogleMap.OnMarkerClickListener, GoogleMap.OnInfoWindowClickListener {
    private final MarkerManager mMarkerManager;
    private final MarkerManager.Collection mMarkers;
    private final MarkerManager.Collection mClusterMarkers;
    private List<Algorithm<T>> mAlgorithms = null;
    private final ReadWriteLock mAlgorithmLock = new ReentrantReadWriteLock();
    private ClusterRenderer<T> mRenderer;

    private GoogleMap mMap;
    private CameraPosition mPreviousCameraPosition;
    private ClusterTask mClusterTask;
    private HashMap<Algorithm<T>, ClusterTask> mClustersTasks;
    private final ReadWriteLock mClusterTaskLock = new ReentrantReadWriteLock();

    private OnClusterItemClickListener<T> mOnClusterItemClickListener;
    private OnClusterInfoWindowClickListener<T> mOnClusterInfoWindowClickListener;
    private OnClusterItemInfoWindowClickListener<T> mOnClusterItemInfoWindowClickListener;
    private OnClusterClickListener<T> mOnClusterClickListener;
    private OnCameraChangeListener mOnCameraChangeListener;
    private GoogleMap.OnMarkerClickListener mOnMarkerClickListener;

    public ClusterManager(Context context, GoogleMap map) {
        this(context, map, new MarkerManager(map));
    }

    public ClusterManager(Context context, GoogleMap map,
            MarkerManager markerManager) {
        mMap = map;
        mMarkerManager = markerManager;
        mClusterMarkers = markerManager.newCollection();
        mMarkers = markerManager.newCollection();
        mRenderer = new DefaultClusterRenderer<T>(context, map, this);
        // mAlgorithm = new PreCachingAlgorithmDecorator<T>(new
        // NonHierarchicalDistanceBasedAlgorithm<T>());
        mClusterTask = null;
        mRenderer.onAdd();
    }

    public MarkerManager.Collection getMarkerCollection() {
        return mMarkers;
    }

    public MarkerManager.Collection getClusterMarkerCollection() {
        return mClusterMarkers;
    }

    public MarkerManager getMarkerManager() {
        return mMarkerManager;
    }

    public void setRenderer(ClusterRenderer<T> view) {
        mClusterTaskLock.writeLock().lock();
        try {
            // Attempt to cancel the in-flight request.
            if (mClustersTasks != null) {
                Iterator it = mClustersTasks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pairs = (Map.Entry)it.next();
                    ((ClusterTask) pairs.getValue()).cancel(true);
                }
                mClustersTasks.clear();
            }
            if (mClusterTask != null) {
                mClusterTask.cancel(true);
            }
            
        } finally {
            mClusterTaskLock.writeLock().unlock();
        }
        if (mRenderer != null) {
            mRenderer.setOnClusterClickListener(null);
            mRenderer.setOnClusterItemClickListener(null);
            mRenderer.setOnClusterInfoWindowClickListener(null);
            mRenderer.setOnClusterItemInfoWindowClickListener(null);
            mRenderer.onRemove();
        }
        
        mClusterMarkers.clear();
        mMarkers.clear();
        mRenderer = view;
        if (mRenderer != null) {
            mRenderer.onAdd();
            mRenderer.setOnClusterClickListener(mOnClusterClickListener);
            mRenderer.setOnClusterInfoWindowClickListener(
                    mOnClusterInfoWindowClickListener);
            mRenderer.setOnClusterItemClickListener(mOnClusterItemClickListener);
            mRenderer.setOnClusterItemInfoWindowClickListener(
                    mOnClusterItemInfoWindowClickListener);
            cluster();
        }
        
    }

    public void addClusterAlgorithm(Algorithm<T> clusterAlgorithm) {
        if (clusterAlgorithm == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        if (mAlgorithms == null) {
            mAlgorithms = new ArrayList<Algorithm<T>>();
        }
        if (!mAlgorithms.contains(clusterAlgorithm)) {
            mAlgorithms.add(clusterAlgorithm);
        }
        mAlgorithmLock.writeLock().unlock();
        clusterAlgo(clusterAlgorithm);
    }

    public void removeClusterAlgorithm(Algorithm<T> clusterAlgorithm) {
        if (clusterAlgorithm == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        mAlgorithms.remove(clusterAlgorithm);
        mAlgorithmLock.writeLock().unlock();
        HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>> sets = new HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>>() {
        };
        sets.add(new Pair<Algorithm<T>, Set<? extends Cluster<T>>>(
                clusterAlgorithm, null));
        mRenderer.onClustersChanged(sets);

    }

    public void clearItems(Algorithm algo) {
        if (algo == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        try {
            algo.clearItems();
        } finally {
            mAlgorithmLock.writeLock().unlock();
        }
        clusterAlgo(algo);
    }

    public void addItems(Collection<T> items, Algorithm algo) {
        if (algo == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        try {
            algo.addItems(items);
        } finally {
            mAlgorithmLock.writeLock().unlock();
        }
        clusterAlgo(algo);
    }

    public void addItem(T myItem, Algorithm algo) {
        if (algo == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        try {
            algo.addItem(myItem);
        } finally {
            mAlgorithmLock.writeLock().unlock();
        }
        clusterAlgo(algo);
    }

    public void removeItem(T item, Algorithm algo) {
        if (algo == null) {
            return;
        }
        mAlgorithmLock.writeLock().lock();
        try {
            algo.removeItem(item);
        } finally {
            mAlgorithmLock.writeLock().unlock();
        }
        clusterAlgo(algo);
    }

    /**
     * Force a re-cluster. You may want to call this after adding new item(s).
     */
    public void cluster() {
        if (mMap == null) {
            return;
        }
        CameraPosition pos = mMap.getCameraPosition();
        Projection projection = mMap.getProjection();
        if (pos == null) {
            return;
        }
        final float zoom = pos.zoom;
        mClusterTaskLock.writeLock().lock();
        try {
            // Attempt to cancel the in-flight request.
            if (mClustersTasks != null) {
                Iterator it = mClustersTasks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pairs = (Map.Entry)it.next();
                    ((ClusterTask) pairs.getValue()).cancel(true);
                }
                mClustersTasks.clear();
            }
            if (mClusterTask != null) {
                mClusterTask.cancel(true);
            }
            mClusterTask = new ClusterTask(null);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                mClusterTask.execute(zoom,
                        projection.getVisibleRegion().latLngBounds);
            } else {
                mClusterTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        zoom, projection.getVisibleRegion().latLngBounds);
            }
        } finally {
            mClusterTaskLock.writeLock().unlock();
        }
    }

    /**
     * Force a re-cluster. You may want to call this after adding new item(s).
     */
    public void clusterAlgo(Algorithm algo) {
        if (mMap == null || mClusterTask != null) {
            return;
        }
        CameraPosition pos = mMap.getCameraPosition();
        Projection projection = mMap.getProjection();
        if (pos == null) {
            return;
        }
        final float zoom = pos.zoom;
        mClusterTaskLock.writeLock().lock();
        try {
            ClusterTask task = null;
            if (mClustersTasks != null) {
                task = mClustersTasks.get(algo);
                if (task != null) {
                    task.cancel(true);
                }
            } else {
                mClustersTasks = new HashMap<Algorithm<T>, ClusterTask>();
            }
            task = new ClusterTask(algo);
            mClustersTasks.put(algo, task);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                task.execute(zoom,
                        projection.getVisibleRegion().latLngBounds);
            } else {
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        zoom, projection.getVisibleRegion().latLngBounds);
            }
        } finally {
            mClusterTaskLock.writeLock().unlock();
        }
    }

    /**
     * Might re-cluster.
     *
     * @param cameraPosition
     */
    
    Handler cameraHandler = new CameraChangeHandler();
    private static final int MESSAGE_CLUSTER = 0;
    class CameraChangeHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
              if (msg.what ==  MESSAGE_CLUSTER) {
                  cluster();
              }
            }
        }
    
    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        if (mRenderer instanceof GoogleMap.OnCameraChangeListener) {
            ((GoogleMap.OnCameraChangeListener) mRenderer)
                    .onCameraChange(cameraPosition);
        }

        // Don't re-compute clusters if the map has just been
        // panned/tilted/rotated.
        CameraPosition position = mMap.getCameraPosition();
        if (mPreviousCameraPosition == null
                || (mPreviousCameraPosition.zoom != position.zoom)) {
            cameraHandler.removeMessages(MESSAGE_CLUSTER);
            cameraHandler.sendEmptyMessageDelayed(MESSAGE_CLUSTER, 300);
        }
        mPreviousCameraPosition = mMap.getCameraPosition();
        if (mOnCameraChangeListener != null) {
            mOnCameraChangeListener.onCameraChange(cameraPosition);
        }
    }

    @Override
    public boolean onMarkerClick(Marker marker) {
        if (getMarkerManager().onMarkerClick(marker)) {
            return true;
        }
        if (mOnMarkerClickListener != null) {
            return mOnMarkerClickListener.onMarkerClick(marker);
        }
        return false;
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        getMarkerManager().onInfoWindowClick(marker);
    }

    public void removeItems() {
        mAlgorithmLock.readLock().lock();
        try {
            for (Algorithm algo : mAlgorithms) {
                algo.clearItems();
            }
        } finally {
            mAlgorithmLock.readLock().unlock();
        }
        mRenderer.clearCache();
    }

    public void removeItemsNotInRectangle(LatLngBounds bounds) {
        mAlgorithmLock.readLock().lock();
        try {
            for (Algorithm algo : mAlgorithms) {
                algo.removeItemsNotInRectangle(bounds);
            }
        } finally {
            mAlgorithmLock.readLock().unlock();
        }
    }

    /**
     * Runs the clustering algorithm in a background thread, then re-paints when
     * results come back.
     */
    private class ClusterTask extends
            AsyncTask<Object, Void, HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>>> {
        private final Algorithm algorithm;

        public ClusterTask(Algorithm algorithm) {
            super();
            this.algorithm = algorithm;
        }

        @SuppressWarnings({ "serial", "finally" })
        @Override
        protected HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>> doInBackground(
                Object... args) {
            Float zoom = (Float) args[0];
            LatLngBounds region = (LatLngBounds) args[1];
            HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>> sets = new HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>>() {
            };
            if (algorithm != null) {
                sets.add(new Pair<Algorithm<T>, Set<? extends Cluster<T>>>(
                        algorithm, algorithm.getClusters(zoom, region)));
                return sets;
            } else {
                mAlgorithmLock.readLock().lock();
                try {
                    for (Algorithm algo : mAlgorithms) {
                        sets.add(
                                new Pair<Algorithm<T>, Set<? extends Cluster<T>>>(
                                        algo, algo.getClusters(zoom, region)));
                    }
                } finally {
                    mAlgorithmLock.readLock().unlock();
                    return sets;
                }
            }

        }

        @Override
        protected void onPostExecute(
                HashSet<Pair<Algorithm<T>, Set<? extends Cluster<T>>>> sets) {
            if (algorithm != null) {
                mClustersTasks.remove(algorithm);
            } else {
                mClusterTask = null;
            }
            mRenderer.onClustersChanged(sets);
        }
    }

    /**
     * Sets a callback that's invoked when a Cluster is tapped. Note: For this
     * listener to function, the ClusterManager must be added as a click
     * listener to the map.
     */
    public void setOnClusterClickListener(OnClusterClickListener<T> listener) {
        mOnClusterClickListener = listener;
        if (mRenderer != null) {
            mRenderer.setOnClusterClickListener(listener);
        }
    }

    /**
     * Sets a callback that's invoked when a Cluster is tapped. Note: For this
     * listener to function, the ClusterManager must be added as a info window
     * click listener to the map.
     */
    public void setOnClusterInfoWindowClickListener(
            OnClusterInfoWindowClickListener<T> listener) {
        mOnClusterInfoWindowClickListener = listener;
        if (mRenderer != null) {
            mRenderer.setOnClusterInfoWindowClickListener(listener);
        }
    }

    /**
     * Sets a callback that's invoked when an individual ClusterItem is tapped.
     * Note: For this listener to function, the ClusterManager must be added as
     * a click listener to the map.
     */
    public void setOnClusterItemClickListener(
            OnClusterItemClickListener<T> listener) {
        mOnClusterItemClickListener = listener;
        if (mRenderer != null) {
            mRenderer.setOnClusterItemClickListener(listener);
        }
    }

    /**
     * Sets a callback that's invoked when an individual ClusterItem's Info
     * Window is tapped. Note: For this listener to function, the ClusterManager
     * must be added as a info window click listener to the map.
     */
    public void setOnClusterItemInfoWindowClickListener(
            OnClusterItemInfoWindowClickListener<T> listener) {
        mOnClusterItemInfoWindowClickListener = listener;
        if (mRenderer != null) {
            mRenderer.setOnClusterItemInfoWindowClickListener(listener);
        }
    }

    public void setOnCameraChangeListener(OnCameraChangeListener listener) {
        mOnCameraChangeListener = listener;
    }
    
    public void setOnMarkerClickListener(GoogleMap.OnMarkerClickListener listener) {
        mOnMarkerClickListener = listener;
    }

    /**
     * Called when a Cluster is clicked.
     */
    public interface OnClusterClickListener<T extends ClusterItem> {
        public boolean onClusterClick(Cluster<T> cluster);
    }

    /**
     * Called when a Cluster's Info Window is clicked.
     */
    public interface OnClusterInfoWindowClickListener<T extends ClusterItem> {
        public void onClusterInfoWindowClick(Cluster<T> cluster);
    }

    /**
     * Called when an individual ClusterItem is clicked.
     */
    public interface OnClusterItemClickListener<T extends ClusterItem> {
        public boolean onClusterItemClick(T item);
    }

    /**
     * Called when an individual ClusterItem's Info Window is clicked.
     */
    public interface OnClusterItemInfoWindowClickListener<T extends ClusterItem> {
        public void onClusterItemInfoWindowClick(T item);
    }
}
