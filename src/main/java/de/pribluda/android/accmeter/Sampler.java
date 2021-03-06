package de.pribluda.android.accmeter;


import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * gather samples from accelerometer and pipe them into designated sinks
 *
 * @author Konstantin Pribluda
 */
public class Sampler {

    public static final String LOG_TAG = "strokeCounter.detector";

    private static Sampler instance;

    // delay between updates
    private long updateDelay = 1000;
    // window size for fft
    private int windowSize = 128;
    //  configured sensor delay
    private int sensorDelay;

    private SensorManager sensorManager;


    private double[] buffer;
    private double real[];
    private double imaginary[];
    // array index
    private int index;

    // use copy on write to prevent eventual race conditions on sink list
    private final List<SampleSink> sinkList = new CopyOnWriteArrayList<SampleSink>();

    private long eventFrequency;

    /**
     * active worker if any
     */
    private Worker worker;


    public Sampler(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        sensorDelay = SensorManager.SENSOR_DELAY_GAME;
    }


    public static Sampler getInstance(Context context) {
        if (instance == null) {
            instance = new Sampler(context);
        }
        return instance;
    }


    public void addSink(SampleSink sink) {
        if (!sinkList.contains(sink)) {
            sinkList.add(sink);
        }
        start();


    }

    public void start() {
        // added first sink to list, create and start worker
        if (null == worker && !sinkList.isEmpty()) {
            worker = new Worker(sensorManager, windowSize, sensorDelay, updateDelay, sinkList);
            worker.start();
        }
    }

    public void removeSink(SampleSink sink) {
        sinkList.remove(sink);
        if (sinkList.isEmpty()) {
            stop();
        }
    }


    public void restart() {
        stop();
        start();

    }

    public void stop() {
        if(worker != null) {
            worker.stop();
            worker = null;
        }
    }

    /**
     * configured window size
     *
     * @return
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * adjust window size. needs restart to take effect. Only power of 2 ( 32, 64 ... 512 ) values are acceptable
     * for FFT ( 1024 is out of bounds ) ,  most useful values are 64 / 128 / 256
     *
     * @param windowSize
     */
    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public long getUpdateDelay() {
        return updateDelay;
    }

    public void setUpdateDelay(long updateDelay) {
        this.updateDelay = updateDelay;
    }

    /**
     * configured sensor delay.  use constants from  SensorManager class.  Changes will be actived after restart
     * DELAY_GAME (default) seems to be most uniform,  but does not send events if there is no changes below some
     * threshold, DELAY_FASTEST sends always but is not uniform.
     * threshold, DELAY_FASTEST sends always but is not uniform.
     *
     * @return
     */
    public int getSensorDelay() {
        return sensorDelay;
    }

    public void setSensorDelay(int sensorDelay) {
        this.sensorDelay = sensorDelay;
    }


    public static enum RunnerState {
        // does not run
        STOPPED,
        // active
        RUNNING,
        // stop was requested
        STOPPING
    }


    public static class Worker implements SensorEventListener, Runnable {

        private final SensorManager sensorManager;
        private final double[] buffer;
        private final double[] real;
        private final double[] imaginary;
        private final FFT fft;
        private int index;


        // delay between updates
        private final long updateDelay;
        // window size for fft
        private final int windowSize;
        //  configured sencor delay
        private final int sensorDelay;

        private final List<SampleSink> sinkList;

        private RunnerState state = RunnerState.STOPPED;

        public Worker(final SensorManager sensorManager, final int windowSize, final int sensorDelay, final long updateDelay, final List<SampleSink> sinkList) {
            this.sensorManager = sensorManager;
            buffer = new double[windowSize];
            real = new double[windowSize];
            Arrays.fill(real, SensorManager.GRAVITY_EARTH);
            imaginary = new double[windowSize];
            fft = new FFT(windowSize);
            index = 0;
            this.sensorDelay = sensorDelay;
            this.windowSize = windowSize;
            this.updateDelay = updateDelay;
            this.sinkList = sinkList;
        }


        public void start() {

            final List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
            if (!sensorList.isEmpty()) {
                state = RunnerState.RUNNING;
                sensorManager.registerListener(this, sensorList.get(0), sensorDelay);
                new Thread(this).start();
            }
        }


        public void stop() {
            if (RunnerState.RUNNING == state) {
                sensorManager.unregisterListener(this);
                state = RunnerState.STOPPING;
            }
        }


        private void startPusherThread() {

        }

        /**
         * receive sensor event and place it into  buffer
         *
         * @param sensorEvent
         */
        public void onSensorChanged(SensorEvent sensorEvent) {

            // we are only interested in accelerometer events
            if (Sensor.TYPE_ACCELEROMETER == sensorEvent.sensor.getType()) {
                // compute modulo
                double modulo = Math.sqrt(sensorEvent.values[0] * sensorEvent.values[0] + sensorEvent.values[1] * sensorEvent.values[1] + sensorEvent.values[2] * sensorEvent.values[2]);
                // store difference
                buffer[index] = modulo;

                index++;
                index %= windowSize;


            }
        }

        public void onAccuracyChanged(Sensor sensor, int i) {

        }


        @Override
        public void run() {
            long scheduled = System.currentTimeMillis() + updateDelay;
            while (RunnerState.RUNNING == state) {
                try {
                    long delay = scheduled - System.currentTimeMillis();
                    if (delay < 0) {
                        delay = 0;
                        scheduled = System.currentTimeMillis();
                    }
                    Thread.sleep(delay);
                    scheduled = scheduled + updateDelay;
                } catch (InterruptedException e) {
                    // ignore it
                }
                updateData();
            }
            state = RunnerState.STOPPED;
        }


        /**
         * compute fft and update  file
         */
        private void updateData() {

            // calculate fft
            for (int i = 0; i < buffer.length; i++) {
                real[i] = buffer[(index + i + 1) % buffer.length];
            }

            Arrays.fill(imaginary, 0);

            fft.fft(real, imaginary);

            // create sample object
            final Sample sample = new Sample();
            //  can not use arrays, as androis sports only 1.5 without copy of
            sample.setReal(new double[real.length]);
            System.arraycopy(real, 0, sample.getReal(), 0, real.length);
            sample.setImaginary(new double[imaginary.length]);
            System.arraycopy(imaginary, 0, sample.getImaginary(), 0, imaginary.length);

            sample.setTimestamp(System.currentTimeMillis());

            pushSample(sample);
        }


        /*
        * push samples to registered sink
        * @param  sample
        */
        private void pushSample(Sample sample) {
            for (SampleSink sink : sinkList) {
                sink.put(sample);
            }
        }

    }
}
