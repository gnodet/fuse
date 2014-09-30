/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.resolver.util;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class CopyOnWriteList<T> extends AbstractCollection<T> implements List<T> {

    Object[] data;

    public CopyOnWriteList() {
        data = new Object[0];
    }

    public CopyOnWriteList(Collection<T> col) {
        if (col instanceof CopyOnWriteList) {
            data = ((CopyOnWriteList) col).data;
        } else {
            data = col.toArray(new Object[col.size()]);
        }
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            int idx = 0;
            public boolean hasNext() {
                return idx < data.length;
            }
            public T next() {
                return (T) data[idx++];
            }
            public void remove() {
                CopyOnWriteList.this.remove(--idx);
            }
        };
    }

    @Override
    public int size() {
        return data.length;
    }

    public boolean addAll(int index, Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    public T get(int index) {
        return (T) data[index];
    }

    public T set(int index, T element) {
        throw new UnsupportedOperationException();
    }

    public void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    public T remove(int index) {
        Object[] elements = data;
        int len = elements.length;
        T oldValue = (T) elements[index];
        Object[] newElements = new Object[len - 1];
        int numMoved = len - index - 1;
        if (index > 0) {
            System.arraycopy(elements, 0, newElements, 0, index);
        }
        if (numMoved > 0) {
            System.arraycopy(elements, index + 1, newElements, index, numMoved);
        }
        data = newElements;
        return oldValue;
    }

    public int indexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    public int lastIndexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    public ListIterator<T> listIterator() {
        throw new UnsupportedOperationException();
    }

    public ListIterator<T> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    public List<T> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof CopyOnWriteList && ((CopyOnWriteList) o).data == data) {
            return true;
        }
        if (!(o instanceof List))
            return false;

        Iterator<T> e1 = iterator();
        Iterator e2 = ((List) o).iterator();
        while (e1.hasNext() && e2.hasNext()) {
            T o1 = e1.next();
            Object o2 = e2.next();
            if (!(o1==null ? o2==null : o1.equals(o2)))
                return false;
        }
        return !(e1.hasNext() || e2.hasNext());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
