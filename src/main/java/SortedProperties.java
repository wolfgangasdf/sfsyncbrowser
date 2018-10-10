import org.jetbrains.annotations.NotNull;

import java.util.*;


// https://stackoverflow.com/questions/10275862/how-to-sort-properties-in-java
// can't do in kotlin: https://youtrack.jetbrains.com/issue/KT-6653
public class SortedProperties extends Properties {
    @NotNull
    @Override
    public Set<Object> keySet() {
        return Collections.unmodifiableSet(new TreeSet<>(super.keySet()));
    }

    @NotNull
    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {

        Set<Map.Entry<Object, Object>> set1 = super.entrySet();
        Set<Map.Entry<Object, Object>> set2 = new LinkedHashSet<>(set1.size());

        Iterator<Map.Entry<Object, Object>> iterator = set1.stream().sorted(Comparator.comparing(o -> o.getKey().toString())).iterator();

        while (iterator.hasNext())
            set2.add(iterator.next());

        return set2;
    }

    @Override
    public synchronized Enumeration<Object> keys() {
        return Collections.enumeration(new TreeSet<>(super.keySet()));
    }
}
