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

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A cluster whose center is determined upon creation.
 */
public class StaticCluster<T extends ClusterItem> implements Cluster<T> {
    private LatLng mCenter;
    private LatLngBounds mBounds = null;
    private final List<T> mItems = new ArrayList<T>();

    public StaticCluster() {
    }

    public boolean add(T t) {
        return mItems.add(t);
    }

    @Override
    public LatLng getPosition() {
        return mCenter;
    }
    
    public LatLngBounds getBounds() {
        return mBounds;
    }

    public boolean remove(T t) {
        return mItems.remove(t);
    }

    @Override
    public Collection<T> getItems() {
        return mItems;
    }

    @Override
    public int getSize() {
        return mItems.size();
    }
    
    public void update() {
        synchronized(mItems) {
            if (mItems.size() == 1) {
                final LatLng pos = mItems.get(0).getPosition();
                mBounds = null;
                mCenter = pos;
            } else {
                for (T item : mItems) {
                    final LatLng pos = item.getPosition();
                    if (mBounds == null) {
                        mBounds = new LatLngBounds(pos, pos);
                    } else {
                        mBounds = mBounds.including(pos);
                    }
                }
                mCenter = mBounds.getCenter();
            }
            
        }
    }

    @Override
    public String toString() {
        return "StaticCluster{" +
                "mCenter=" + mCenter +
                ", mItems.size=" + mItems.size() +
                '}';
    }
}