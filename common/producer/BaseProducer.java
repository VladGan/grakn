/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.common.producer;

import grakn.core.common.concurrent.ExecutorService;
import grakn.core.common.iterator.ResourceIterator;

public class BaseProducer<T> implements Producer<T> {

    private ResourceIterator<T> iterator;

    BaseProducer(ResourceIterator<T> iterator) {
        this.iterator = iterator;
    }

    @Override
    public void produce(Sink<T> sink, int count) {
        ExecutorService.forkJoinPool().submit(() -> {
            for (int i = 0; i < count; i++) {
                if (iterator.hasNext()) {
                    sink.put(iterator.next());
                } else {
                    sink.done(this);
                    break;
                }
            }
        });
    }

    @Override
    public void recycle() {
        iterator.recycle();
    }
}
