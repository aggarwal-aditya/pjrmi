package com.deshaw.hypercube;

// Recreate with `cog.py -rc DoubleMappedHypercube.java`
// [[[cog
//     import cog
//     import numpy
//     import primitive_mapped_hypercube
//
//     cog.outl(primitive_mapped_hypercube.generate(numpy.float64))
// ]]]
import java.io.IOException;
import java.io.UncheckedIOException;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;

/**
 * A hypercube which has {@code double} values as its elements and stores
 * them in a memory-mapped file.
 *
 * <p>The expected layout of the memory-mapped file is such that a {@code numpy}
 * array can also wrap it on the Python side using something like:
 * <pre>
 *    array = numpy.memmap(filename, dtype=numpy.float64, mode='w+', shape=shape, order='C')
 * </pre>
 */
public class DoubleMappedHypercube
    extends AbstractDoubleHypercube
{
    /**
     * The shift for the max buffer size.
     */
    private static final int MAX_BUFFER_SHIFT = 30;

    /**
     * The largest buffer size.
     */
    private static final long MAX_BUFFER_SIZE = (1L << MAX_BUFFER_SHIFT);

    /**
     * The mask for buffer index tweaking.
     */
    private static final long MAX_BUFFER_MASK = MAX_BUFFER_SIZE - 1;

    /**
     * The array of buffers which we hold as the underlying
     * {@link MappedByteBuffer}.
     */
    private final MappedByteBuffer[] myMappedBuffers;

    /**
     * The array of buffers-of-elements which we hold. We have multiple buffers
     * since we might have a size which is larger than what can be represented
     * by a single array. (I.e. more than 2^30 elements.)
     */
    private final DoubleBuffer[] myBuffers;

    /**
     * Constructor.
     */
    public DoubleMappedHypercube(final String path,
                                        final Dimension<?>[] dimensions)
        throws IllegalArgumentException,
               IOException,
               NullPointerException
    {
        this(FileChannel.open(Path.of(path),
                              StandardOpenOption.READ, StandardOpenOption.WRITE),
             dimensions);
    }

    /**
     * Constructor.
     */
    public DoubleMappedHypercube(final FileChannel channel,
                                        final Dimension<?>[] dimensions)
        throws IllegalArgumentException,
               IOException,
               NullPointerException
    {
        super(dimensions);

        int numBuffers = (int)(size >>> MAX_BUFFER_SHIFT);
        if (numBuffers * MAX_BUFFER_SIZE < size) {
            numBuffers++;
        }
        myMappedBuffers = new MappedByteBuffer[numBuffers];
        myBuffers       = new DoubleBuffer[numBuffers];

        final long tail = (size & MAX_BUFFER_MASK);
        for (int i=0; i < numBuffers; i++) {
            final long sz =
                ((i+1 < numBuffers) ? MAX_BUFFER_SIZE : tail) * Double.BYTES;
            final long position = (long)i * MAX_BUFFER_SIZE * Double.BYTES;
            if (LOG.isLoggable(Level.FINEST)) {
                LOG.finest(
                    "Mapping in buffer[" + i + "] from " + channel + " " +
                    "at position " + position + " and size " + sz
                );
            }
            final MappedByteBuffer buffer =
                channel.map(FileChannel.MapMode.READ_WRITE, position, sz);
            buffer.order(ByteOrder.nativeOrder());
            myMappedBuffers[i] = buffer;
            myBuffers      [i] = buffer.asDoubleBuffer();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush()
        throws IOException
    {
        try {
            for (MappedByteBuffer buffer : myMappedBuffers) {
                buffer.force();
            }
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void toFlattenedObjs(final long srcPos,
                                final Double[] dst,
                                final int dstPos,
                                final int length)
        throws IllegalArgumentException,
               IndexOutOfBoundsException,
               UnsupportedOperationException
    {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest(
                "Flattening with " +
                "srcPos=" + srcPos + " dst=" + dst + " dstPos=" + dstPos + " " +
                "length=" + length
            );
        }

        // Check the arguments
        checkFlattenArgs(srcPos, dst, dstPos, length);

        for (int i=0; i < length; i++) {
            final long pos = srcPos + i;
            final DoubleBuffer buffer =
                myBuffers[(int)(pos >>> MAX_BUFFER_SHIFT)];
            final double d = buffer.get((int)(pos & MAX_BUFFER_MASK));
            dst[dstPos + i] = Double.valueOf(d);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fromFlattenedObjs(final Double[] src,
                                  final int       srcPos,
                                  final long      dstPos,
                                  final int       length)
        throws IllegalArgumentException,
               IndexOutOfBoundsException,
               NullPointerException
    {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest(
                "Unflattening with " +
                "src=" + src + " srcPos=" + srcPos + " dstPos=" + dstPos + " " +
                "length=" + length
            );
        }

        // Check input
        checkUnflattenArgs(srcPos, dstPos, length);
        if (src == null) {
            throw new NullPointerException("Given a null array");
        }
        if (src.length - srcPos < length) {
            throw new IndexOutOfBoundsException(
                "Source position, " + srcPos + ", " +
                "plus length ," + length + ", " +
                "was greater than the array size, " + src.length
            );
        }

        // Safe to copy in
        for (int i=0; i < length; i++) {
            final long pos = dstPos + i;
            final DoubleBuffer buffer =
                myBuffers[(int)(pos >>> MAX_BUFFER_SHIFT)];
            final Double value = src[srcPos + i];
            buffer.put(
                (int)(pos & MAX_BUFFER_MASK),
                (value == null) ? Double.NaN : value.doubleValue()
            );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void toFlattened(final long      srcPos,
                            final double[] dst,
                            final int       dstPos,
                            final int       length)
        throws IllegalArgumentException,
               IndexOutOfBoundsException,
               UnsupportedOperationException
    {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest(
                "Flattening with " +
                "srcPos=" + srcPos + " dst=" + dst + " dstPos=" + dstPos + " " +
                "length=" + length
            );
        }

        // Check the arguments
        checkFlattenArgs(srcPos, dst, dstPos, length);
        if (dst == null) {
            throw new NullPointerException("Given a null array");
        }

        // These can never differ by more than 1 since length is an int. And the
        // end is non-inclusive since we're counting fence, not posts.
        final int startIdx = (int)((srcPos             ) >>> MAX_BUFFER_SHIFT);
        final int endIdx   = (int)((srcPos + length - 1) >>> MAX_BUFFER_SHIFT);
        if (startIdx == endIdx) {
            // What to copy? Try to avoid the overhead of the system call. If we are
            // striding through the cube then we may well have just the one.
            final DoubleBuffer buffer = myBuffers[startIdx];
            switch (length) {
            case 0:
                // NOP
                break;

            case 1:
                // Just the one element
                dst[dstPos] = buffer.get((int)(srcPos & MAX_BUFFER_MASK));
                break;

            default:
                // Copy within the same sub-array
                if (buffer != null) {
                    // Move the buffer pointer and do the bulk operation
                    try {
                        buffer.position((int)(srcPos & MAX_BUFFER_MASK));
                        buffer.get(dst, dstPos, length);
                    }
                    catch (BufferOverflowException e) {
                        throw new IndexOutOfBoundsException(e.toString());
                    }
                }
                else {
                    Arrays.fill(dst, dstPos, dstPos + length, Double.NaN);
                }
            }
        }
        else {
            // Split into two copies
            final DoubleBuffer startBuffer = myBuffers[startIdx];
            final DoubleBuffer endBuffer   = myBuffers[endIdx  ];
            if (startBuffer != null && endBuffer != null) {
                final int startPos    = (int)(srcPos & MAX_BUFFER_MASK);
                final int startLength = length - (startBuffer.limit() - startPos);
                final int endLength   = length - startLength;
                try {
                    startBuffer.position((int)(startPos & MAX_BUFFER_MASK));
                    endBuffer  .position(0);
                    startBuffer.get(dst, dstPos,               startLength);
                    endBuffer  .get(dst, dstPos + startLength, endLength);
                }
                catch (BufferUnderflowException e) {
                    throw new IndexOutOfBoundsException(e.toString());
                }
            }
            else {
                Arrays.fill(dst, dstPos, dstPos + length, Double.NaN);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fromFlattened(final double[] src,
                              final int       srcPos,
                              final long      dstPos,
                              final int       length)
        throws IllegalArgumentException,
               IndexOutOfBoundsException,
               NullPointerException
    {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest(
                "Unflattening with " +
                "src=" + src + " srcPos=" + srcPos + " dstPos=" + dstPos + " " +
                "length=" + length
            );
        }

        // Sanitise input
        checkUnflattenArgs(srcPos, dstPos, length);
        if (src == null) {
            throw new NullPointerException("Given a null array");
        }

        // These can never differ by more than 1 since length is an int
        final int startIdx = (int)((dstPos             ) >>> MAX_BUFFER_SHIFT);
        final int endIdx   = (int)((dstPos + length - 1) >>> MAX_BUFFER_SHIFT);

        // What to copy? Try to avoid the overhead of the system call. If we are
        // striding through the cube then we may well have just the one.
        if (startIdx == endIdx) {
            // Get the buffer
            final DoubleBuffer buffer = myBuffers[startIdx];

            // And handle it
            switch (length) {
            case 0:
                // NOP
                break;

            case 1:
                // Just the one element
                if (srcPos >= src.length) {
                    throw new IndexOutOfBoundsException(
                        "Source position, " + srcPos + ", " +
                        "plus length ," + length + ", " +
                        "was greater than the array size, " + src.length
                    );
                }
                buffer.put((int)(dstPos & MAX_BUFFER_MASK), src[srcPos]);
                break;

            default:
                // Standard copy within the same sub-buffer
                if (src.length - srcPos < length) {
                    throw new IndexOutOfBoundsException(
                        "Source position, " + srcPos + ", " +
                        "plus length ," + length + ", " +
                        "was greater than the array size, " + src.length
                    );
                }
                buffer.position((int)(dstPos & MAX_BUFFER_MASK));
                buffer.put(src, srcPos, length);
                break;
            }
        }
        else {
            // Split into two copies
            final DoubleBuffer startBuffer = myBuffers[startIdx];
            final DoubleBuffer endBuffer   = myBuffers[endIdx  ];
            final int startPos    = (int)(dstPos & MAX_BUFFER_MASK);
            final int startLength = length - (startBuffer.limit() - startPos);
            final int endLength   = length - startLength;
            try {
                startBuffer.position((int)(startPos & MAX_BUFFER_MASK));
                endBuffer  .position(0);
                startBuffer.put(src, srcPos,               startLength);
                endBuffer  .put(src, srcPos + startLength, endLength);
            }
            catch (BufferOverflowException e) {
                throw new IndexOutOfBoundsException(e.toString());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double get(final long... indices)
        throws IndexOutOfBoundsException
    {
        return getAt(toOffset(indices));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void set(final double d, final long... indices)
        throws IndexOutOfBoundsException
    {
        setAt(toOffset(indices), d);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Double getObjectAt(final long index)
        throws IndexOutOfBoundsException
    {
        return Double.valueOf(getAt(index));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setObjectAt(final long index, final Double value)
        throws IndexOutOfBoundsException
    {
        setAt(index, (value == null) ? Double.NaN : value.doubleValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getAt(final long index)
        throws IndexOutOfBoundsException
    {

        final DoubleBuffer buffer =
            myBuffers[(int)(index >>> MAX_BUFFER_SHIFT)];
        return buffer.get((int)(index & MAX_BUFFER_MASK));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAt(final long index, final double value)
        throws IndexOutOfBoundsException
    {
        final DoubleBuffer buffer =
            myBuffers[(int)(index >>> MAX_BUFFER_SHIFT)];
        buffer.put((int)(index & MAX_BUFFER_MASK), value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String,Boolean> createFlags()
    {
        final Map<String,Boolean> result = super.createFlags();
        result.put("aligned",      true);
        result.put("behaved",      true);
        result.put("c_contiguous", true);
        result.put("owndata",      true);
        result.put("writeable",    true);
        return result;
    }
}

// [[[end]]] (checksum: 1280e78a1017de9dbba281c7c39f9746)