package com.nstut.simplyscreens.compat;
import java.util.*;
public final class Java8 {
    private Java8() {}
    public static java.nio.file.Path path(String first, String... more) { return java.nio.file.Paths.get(first, more); }
    @SafeVarargs public static <T> List<T> list(T... values) { return listCopy(Arrays.asList(values)); }
    public static <T> List<T> listCopy(Collection<? extends T> values) { List<T> copy=new ArrayList<>(); for(T value:values) copy.add(Objects.requireNonNull(value)); return Collections.unmodifiableList(copy); }
    @SafeVarargs public static <T> Set<T> set(T... values) { Set<T> result=new HashSet<>(); for(T value:values) if(!result.add(Objects.requireNonNull(value))) throw new IllegalArgumentException("Duplicate element"); return Collections.unmodifiableSet(result); }
    public static <T> Set<T> setCopy(Collection<? extends T> values) { Set<T> copy=new HashSet<>(); for(T value:values) copy.add(Objects.requireNonNull(value)); return Collections.unmodifiableSet(copy); }
    @SuppressWarnings("unchecked") public static <K,V> Map<K,V> map(Object... pairs) { Map<K,V> result=new HashMap<>(); for(int i=0;i<pairs.length;i+=2) { K key=(K)Objects.requireNonNull(pairs[i]); V val=(V)Objects.requireNonNull(pairs[i+1]); if(result.put(key,val)!=null) throw new IllegalArgumentException("Duplicate key"); } return Collections.unmodifiableMap(result); }
    public static <K,V> Map<K,V> mapCopy(Map<? extends K,? extends V> values) { Map<K,V> copy=new HashMap<>(); values.forEach((k,v)->copy.put(Objects.requireNonNull(k),Objects.requireNonNull(v))); return Collections.unmodifiableMap(copy); }
    public static <T> T requireNonNullElse(T value,T fallback) { return value!=null?value:Objects.requireNonNull(fallback); }
    public static boolean isBlank(String text) { for(int i=0;i<text.length();) { int cp=text.codePointAt(i); if(!Character.isWhitespace(cp)) return false; i+=Character.charCount(cp); } return true; }
    public static String repeat(String text,int count) { if(count<0) throw new IllegalArgumentException("Negative repeat count"); StringBuilder out=new StringBuilder(Math.multiplyExact(text.length(),count)); for(int i=0;i<count;i++) out.append(text); return out.toString(); }
    public static String readString(java.nio.file.Path path) throws java.io.IOException { return new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
    public static java.nio.file.Path writeString(java.nio.file.Path path, CharSequence value) throws java.io.IOException { return java.nio.file.Files.write(path,value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static <T> T service(Class<T> type) { java.util.Iterator<T> iterator=java.util.ServiceLoader.load(type).iterator(); if(!iterator.hasNext()) throw new IllegalStateException("Missing service: "+type.getName()); return iterator.next(); }

    public static String strip(String value) {
        int begin=0,end=value.length();
        while(begin<end) { int cp=value.codePointAt(begin);if(!Character.isWhitespace(cp))break;begin+=Character.charCount(cp); }
        while(end>begin) { int cp=value.codePointBefore(end);if(!Character.isWhitespace(cp))break;end-=Character.charCount(cp); }
        return value.substring(begin,end);
    }
    public static java.nio.file.Path writeString(java.nio.file.Path path,CharSequence value,java.nio.charset.Charset encoding,java.nio.file.OpenOption... options) throws java.io.IOException {
        return java.nio.file.Files.write(path,value.toString().getBytes(encoding),options);
    }
    public static byte[] readNBytes(java.io.InputStream input,int count) throws java.io.IOException {
        if(count<0)throw new IllegalArgumentException("Negative byte count");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(Math.min(count,8192));
        byte[] buffer=new byte[Math.min(count,8192)];int remaining=count;
        while(remaining>0) { int n=input.read(buffer,0,Math.min(buffer.length,remaining)); if(n<0)break;
            if(n==0) { int value=input.read();if(value<0)break;out.write(value);remaining--; }
            else { out.write(buffer,0,n);remaining-=n; }
        }
        return out.toByteArray();
    }

    public static byte[] readAllBounded(java.io.InputStream input,int maximum) throws java.io.IOException {
        if(maximum<0 || maximum==Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid byte limit");
        byte[] data=readNBytes(input,maximum+1);
        if(data.length>maximum)throw new java.io.IOException("Image exceeds encoded byte budget");
        return data;
    }
}
