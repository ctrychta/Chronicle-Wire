/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.wire;

import java.util.ArrayList;
import java.util.List;

class ValueInStack {
    final List<ValueInState> freeList = new ArrayList<>();
    int level = 0;

    public ValueInStack() {
        addOne();
    }

    public void reset() {
        level = 0;
        freeList.get(0).reset();
    }

    public void push() {
        level++;
        if (freeList.size() > level) {
            freeList.get(level).reset();
        }
    }

    public void pop() {
        if (level < 0)
            throw new IllegalStateException();
        level--;
    }

    public ValueInState curr() {
        while (freeList.size() <= level)
            addOne();
        return freeList.get(level);
    }

    private void addOne() {
        freeList.add(new ValueInState());
    }
}
