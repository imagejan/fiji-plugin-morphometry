package de.embl.cba.morphometry.segmentation;

import de.embl.cba.morphometry.Algorithms;
import de.embl.cba.morphometry.CoordinateAndValue;
import de.embl.cba.morphometry.IntensityHistogram;
import de.embl.cba.morphometry.Utils;
import de.embl.cba.morphometry.microglia.MicrogliaTrackingSettings;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import static de.embl.cba.morphometry.viewing.BdvViewer.show;
import static de.embl.cba.transforms.utils.Scalings.createRescaledArrayImg;
import static de.embl.cba.transforms.utils.Transforms.getScalingFactors;


public class SimpleSegmenter< T extends RealType< T > & NativeType< T > >
{

	final MicrogliaTrackingSettings settings;
	private RandomAccessibleInterval< BitType > mask;
	final private RandomAccessibleInterval< T > intensity;
	final private boolean showIntermediateResults;

	public SimpleSegmenter( RandomAccessibleInterval< T > intensity, MicrogliaTrackingSettings settings )
	{
		this.intensity = intensity;
		this.settings = settings;
		this.showIntermediateResults = false; //settings.showIntermediateResults;

	}

	public void run()
	{

		/**
		 *  Create working image
		 */

		final double[] workingCalibration = Utils.as2dDoubleArray( settings.workingVoxelSize );

		final RandomAccessibleInterval< T > image = createRescaledArrayImg( intensity, getScalingFactors( settings.inputCalibration, settings.workingVoxelSize ) );

		if ( showIntermediateResults ) show( image, "image isotropic resolution", null, workingCalibration, false );


		/**
		 *  Smooth
		 */

		// TODO


		/**
		 *  Compute offset and threshold
		 */

		final IntensityHistogram intensityHistogram = new IntensityHistogram( image, settings.maxPossibleValueInDataSet, 2 );

		CoordinateAndValue mode = intensityHistogram.getMode();

		final CoordinateAndValue rightHandHalfMaximum = intensityHistogram.getRightHandHalfMaximum();

		double threshold = ( rightHandHalfMaximum.coordinate - mode.coordinate ) * settings.thresholdInUnitsOfBackgroundPeakHalfWidth;
		double offset = mode.coordinate;
		Utils.log( "Intensity offset: " + offset );
		Utils.log( "Threshold: " + ( threshold + offset ) );

		/**
		 * Create mask
		 */

		mask = Algorithms.createMask( image, threshold );

		if ( showIntermediateResults ) show( mask, "mask", null, workingCalibration, false );


		/**
		 * Remove small objects from mask
		 */

		Algorithms.removeSmallRegionsInMask( mask, settings.minimalObjectSize, settings.workingVoxelSize );

		if ( showIntermediateResults ) show( mask, "size filtered mask", null, workingCalibration, false );


	}

	public RandomAccessibleInterval< BitType > getMask()
	{
		return mask;
	}


}
