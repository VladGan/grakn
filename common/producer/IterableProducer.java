/*
 * Copyright (C) 2021 Grakn Labs
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

import grakn.common.collection.Either;
import grakn.core.common.concurrent.ManagedBlockingQueue;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class IterableProducer<T> {

    private static final int BUFFER_MIN_SIZE = 32;
    private static final int BUFFER_MAX_SIZE = 64;

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";

    private final LinkedList<Producer<T>> producers;
    private final Queue queue;
    private final Iterator iterator;

    public IterableProducer(List<Producer<T>> producers) {
        this(producers, BUFFER_MIN_SIZE, BUFFER_MAX_SIZE);
    }

    public IterableProducer(List<Producer<T>> producers, int bufferMinSize, int bufferMaxSize) {
        // TODO: Could we optimise IterableProducer by accepting ResourceIterator<Producer<T>> instead?
        assert !producers.isEmpty();
        this.producers = new LinkedList<>(producers);
        this.queue = new Queue(bufferMinSize, bufferMaxSize);
        this.iterator = new Iterator();
    }

    public IterableProducer<T>.Iterator iterator() {
        return iterator;
    }

    private void mayProduce() {
        synchronized (queue) {
            if (producers.isEmpty()) return;
            int available = queue.max - queue.size() - queue.pending;
//            System.out.println("available: " + available + " queue.size(): " + queue.size() + " queue.pending: " + queue.pending + " total: " + (available + queue.pending));

            if (available > queue.max - queue.min) {
                queue.pending += available;
                assert !producers.isEmpty();
                producers.peek().produce(queue, available);
            }
        }
    }

    private enum State {EMPTY, FETCHED, COMPLETED}

    private static class Done {
        @Nullable
        private final Throwable error;

        private Done(Throwable error) {
            this.error = error;
        }

        public static Done success() {
            return new Done(null);
        }

        public static Done error(Throwable e) {
            return new Done(e);
        }

        public Optional<Throwable> error() {
            return Optional.ofNullable(error);
        }
    }

    public class Iterator implements ResourceIterator<T> {

        private T next;
        private State state;

        Iterator() {
            state = State.EMPTY;
        }

        @Override
        public boolean hasNext() {
            if (state == State.COMPLETED) return false;
            else if (state == State.FETCHED) return true;
            else mayProduce();

            Either<T, Done> result = queue.take();

            if (result.isFirst()) {
                next = result.first();
                state = State.FETCHED;
            } else {
                Done done = result.second();
                recycle();
                state = State.COMPLETED;
                if (done.error().isPresent()) {
                    throw GraknException.of(done.error().get());
                }
            }

            return state == State.FETCHED;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            state = State.EMPTY;
            return next;
        }

        @Override
        public void recycle() {
            producers.forEach(Producer::recycle);
        }
    }

    private class Queue implements Producer.Queue<T> {

        private final ManagedBlockingQueue<Either<T, Done>> blockingQueue;
        private final AtomicBoolean isError;
        private final int min;
        private final int max;
        private int pending;

        private Queue(int bufferMinSize, int max) {
            this.min = bufferMinSize;
            this.max = max;
            this.blockingQueue = new ManagedBlockingQueue<>();
            this.isError = new AtomicBoolean(false);
            this.pending = 0;
        }

        @Override
        public synchronized void put(T item) {
            try {
                blockingQueue.put(Either.first(item));
                pending--;
                assert pending >= 0 || isError.get();
            } catch (InterruptedException e) {
                throw GraknException.of(e);
            }
        }

        @Override
        public synchronized void done() {
            done(null);
        }

        @Override
        public synchronized void done(@Nullable Throwable error) {
            assert !producers.isEmpty();
            producers.removeFirst();
            pending = 0;

            try {
                if (error != null) {
                    blockingQueue.put(Either.second(Done.error(error)));
                } else if (producers.isEmpty()) {
                    blockingQueue.put(Either.second(Done.success()));
                } else {
                    mayProduce();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private Either<T, Done> take() {
            try {
                return blockingQueue.take();
            } catch (InterruptedException e) {
                throw GraknException.of(e);
            }
        }

        private int size() {
            return blockingQueue.size();
        }
    }
}
