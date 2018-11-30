package de.embl.cba.morphometry.spindle;

import de.embl.cba.morphometry.*;
import de.embl.cba.morphometry.geometry.CoordinatesAndValues;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidMLJ;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidsMLJ;
import de.embl.cba.morphometry.regions.Regions;
import de.embl.cba.morphometry.viewing.Viewer3D;
import de.embl.cba.transforms.utils.Transforms;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ops.OpService;
import net.imglib2.*;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegion;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.util.Intervals;

import java.io.File;
import java.util.ArrayList;

import static de.embl.cba.morphometry.Angles.angleOfSpindleAxisToXaxisInRadians;
import static de.embl.cba.morphometry.viewing.BdvViewer.show;
import static de.embl.cba.transforms.utils.Scalings.createRescaledArrayImg;
import static de.embl.cba.transforms.utils.Transforms.getScalingFactors;


public class SpindleMorphometry  < T extends RealType< T > & NativeType< T > >
{

	final SpindleMorphometrySettings settings;
	final OpService opService;

	public SpindleMorphometry( SpindleMorphometrySettings settings, OpService opService )
	{
		this.settings = settings;
		this.opService = opService;
	}

	public void run()
	{

		/**
		 *  Make isotropic
		 */

		Utils.log( "Create isotropic image..." );

		final double[] workingCalibration = Utils.as3dDoubleArray( settings.workingVoxelSize );

		final RandomAccessibleInterval< T > dapi = createRescaledArrayImg( settings.dapiImage, getScalingFactors( settings.inputCalibration, settings.workingVoxelSize ) );
		final RandomAccessibleInterval< T > tubulin = createRescaledArrayImg( settings.tubulinImage, getScalingFactors( settings.inputCalibration, settings.workingVoxelSize ) );

		if ( settings.showIntermediateResults ) show( dapi, "dapi isotropic resolution", null, workingCalibration, false );
		if ( settings.showIntermediateResults ) show( tubulin, "tubulin isotropic resolution", null, workingCalibration, false );


		/**
		 *  Compute offset and threshold
		 */

		final RandomAccessibleInterval< T > dapiDownscaledToSpindleWidth = createRescaledArrayImg( settings.dapiImage, getScalingFactors( settings.inputCalibration, 3.0 ) );
		final double maximumValue = Algorithms.getMaximumValue( dapiDownscaledToSpindleWidth );
		double threshold = maximumValue / 2.0;

		Utils.log( "Dapi threshold: " + threshold );


		/**
		 * Create mask
		 */

		RandomAccessibleInterval< BitType > mask = createMask( dapi, threshold );

		if ( settings.showIntermediateResults ) show( mask, "dapi mask", null, workingCalibration, false );


		/**
		 * Extract metaphase plate object
		 */

		Utils.log( "Extracting metaphase plate object..." );

		Algorithms.removeSmallRegionsInMask( mask, settings.minimalMetaphasePlateVolumeInCalibratedUnits, settings.workingVoxelSize );

		final ImgLabeling< Integer, IntType > labelImg = Utils.asImgLabeling( mask );

		final long radius = (long) ( settings.centralObjectRegionToleranceInCalibratedUnits / settings.workingVoxelSize );
		final LabelRegion< Integer > selectedRegion = Regions.getCentralRegion( labelImg, radius );

		final Img< BitType > metaphasePlate = Algorithms.createMaskFromLabelRegion( selectedRegion, Intervals.dimensionsAsLongArray( labelImg ) );

		if ( settings.showIntermediateResults ) show( metaphasePlate, "meta-phase object", null, workingCalibration, false );


		/**
		 * Morphological filtering
		 *
		 * - it appears that the principal axes determination is not working robustly
		 * if the meta-phase plate is too thick
		 */


		// TODO: maybe only shrink the mask if it is too thick?

		RandomAccessibleInterval< BitType > processedMetaPhasePlate = createProcessedMetaPhasePlate( mask, metaphasePlate );

		if ( settings.showIntermediateResults ) show( processedMetaPhasePlate, "processed metaphase plate", null, workingCalibration, false );



		/**
		 * Compute ellipsoid alignment
		 */

		Utils.log( "Determining meta-phase plate axes..." );

		final EllipsoidMLJ ellipsoidParameters = EllipsoidsMLJ.computeParametersFromBinaryImage( processedMetaPhasePlate );

		Utils.log( "Creating aligned images..." );

		final AffineTransform3D alignmentTransform = EllipsoidsMLJ.createAlignmentTransform( ellipsoidParameters );
		final RandomAccessibleInterval aligendTubulin = Utils.copyAsArrayImg( Transforms.createTransformedView( tubulin, alignmentTransform, new NearestNeighborInterpolatorFactory() ) );
		final RandomAccessibleInterval alignedDapi = Utils.copyAsArrayImg( Transforms.createTransformedView( dapi, alignmentTransform ) );
		final RandomAccessibleInterval alignedProcessedMetaphasePlate = Utils.copyAsArrayImg( Transforms.createTransformedView( processedMetaPhasePlate, alignmentTransform ) );

		if ( settings.showIntermediateResults ) show( alignedDapi, "aligned dapi", Transforms.origin(), workingCalibration, false );
		if ( settings.showIntermediateResults ) show( alignedProcessedMetaphasePlate, "aligned processed meta-phase plate", Transforms.origin(), workingCalibration, false );
		if ( settings.showIntermediateResults ) Viewer3D.show3D( alignedProcessedMetaphasePlate );

		RandomAccessibleInterval< T > interestPoints = Utils.copyAsArrayImg( dapi );
		Utils.setValues( interestPoints, 0.0 );

		final double[] dnaCenter = new double[ 3 ];
		alignmentTransform.inverse().apply( new double[]{0,0,0}, dnaCenter );
		drawPoint( interestPoints, dnaCenter, settings.interestPointsRadius, settings.workingVoxelSize );


		/**
		 * Compute metaphase plate width
		 */

		Utils.log( "Determining widths..." );

		final CoordinatesAndValues dapiProfile = Utils.computeAverageIntensitiesAlongAxis( alignedDapi, settings.maxShortAxisDist, 2, settings.workingVoxelSize );
		if ( settings.showIntermediateResults ) Plots.plot( dapiProfile.coordinates, dapiProfile.values, "distance to center", "dapi intensity along shortest axis" );

		// TODO: compute meta-phase width, using FWHM and write to log window

		/**
		 * Compute spindle length
		 */

		final CoordinatesAndValues tubulinProfile = Utils.computeMaximumIntensitiesAlongAxis( aligendTubulin, settings.maxShortAxisDist, 2, settings.workingVoxelSize );
		if ( settings.showIntermediateResults ) Plots.plot( tubulinProfile.coordinates, tubulinProfile.values, "distance to center", "tubulin maximal intensities" );

		final CoordinatesAndValues tubulinProfileDerivative = Algorithms.computeDerivatives( tubulinProfile, (int) Math.ceil( settings.derivativeDelta / settings.workingVoxelSize ) );
		if ( settings.showIntermediateResults ) Plots.plot( tubulinProfileDerivative.coordinates, tubulinProfileDerivative.values, "distance to center", "tubulin intensity derivative" );

		double[] spindlePoles = getLeftMaxAndRightMinLoc( tubulinProfileDerivative.coordinates, tubulinProfileDerivative.values );

		Utils.log( "Left spindle pole found at: " + spindlePoles[ 0 ] );
		Utils.log( "Right spindle pole found at: " + spindlePoles[ 1 ] );

		addSpindlePolesAsInterestPoints( alignmentTransform, interestPoints, spindlePoles );

		final ArrayList< RealPoint > spindleLengthPoints = new ArrayList<>(  );

		spindleLengthPoints.add( new RealPoint( new double[]{ 0.0, 0.0, 0 } ));
		spindleLengthPoints.add( new RealPoint( new double[]{ 0.0, 0.0, spindlePoles[ 0 ] } ));
		spindleLengthPoints.add( new RealPoint( new double[]{ 0.0, 0.0, spindlePoles[ 1 ] } ));

		if ( settings.showIntermediateResults ) show( aligendTubulin, "aligned tubulin", spindleLengthPoints, workingCalibration, false );


		/**
		 * Create output images
		 */

		// "rotation" rotates the data such that the spindle axis is the zAxis

		AffineTransform3D rotation = alignmentTransform.copy();
		rotation.setTranslation( new double[ 3 ] );

		final double[] zAxis = { 0, 0, 1 };

		// compute the spindle axis in the coordinate system of the input data
		final double[] spindleAxis = new double[ 3 ];
		rotation.inverse().apply( zAxis, spindleAxis );

		// set z-component of spindleAxis to zero, because we want to rotate parallel to coverslip
		spindleAxis[ 2 ] = 0;

		// compute rotation vector
		// note: we do not know in which direction of the spindle the spindleAxis vector points,
		// but we think it does not matter for alignment to the x-axis
		final double angleBetweenXAxisAndSpindleWithVector = angleOfSpindleAxisToXaxisInRadians( spindleAxis );

		AffineTransform3D inputDataRotation = new AffineTransform3D();
		inputDataRotation.rotate( 2,  angleBetweenXAxisAndSpindleWithVector );

		Utils.log( "Rotating input data around z-axis by [degrees]: " + 180 / Math.PI * angleBetweenXAxisAndSpindleWithVector );

		final RandomAccessibleInterval transformedDapiView = Transforms.createTransformedView( dapi, inputDataRotation );
		final RandomAccessibleInterval transformedTubulinView = Transforms.createTransformedView( tubulin, inputDataRotation );
		final RandomAccessibleInterval transformedInterestPointView = Transforms.createTransformedView( interestPoints, inputDataRotation );

//		Bdv bdv = null;
//		if ( settings.showIntermediateResults ) BdvFunctions.show( interestPoints, "" ).getBdvHandle();
//		if ( settings.showIntermediateResults ) bdv = BdvFunctions.show( transformedDapiView, "" ).getBdvHandle();
//		if ( settings.showIntermediateResults ) BdvFunctions.show( transformedInterestPointView, "", BdvOptions.options().addTo( bdv ) );

		Utils.log( "Saving result images ..." );

		saveImagePlus( Utils.asImagePlus( alignedProcessedMetaphasePlate ) );
		saveMaximumProjections( transformedDapiView, "dapi" );
		saveMaximumProjections( transformedTubulinView, "tubulin" );
		saveMaximumProjections( transformedInterestPointView, "points" );

//		if ( settings.showIntermediateResults )  show( transformedView, "side view image", null, workingCalibration, false );
//
//		int a = 1;


	}

	public RandomAccessibleInterval< BitType > createProcessedMetaPhasePlate( RandomAccessibleInterval< BitType > mask, Img< BitType > metaphasePlate )
	{
		Utils.log( "Perform morphological filtering on dapi mask..." );

		RandomAccessibleInterval< BitType > filtered;

		if ( settings.erosionOfDapiMaskInCalibratedUnits > 0 )
		{
			filtered = Algorithms.erode( metaphasePlate, ( int ) Math.ceil( settings.erosionOfDapiMaskInCalibratedUnits / settings.workingVoxelSize ) );
		}
		else
		{
			filtered = mask;
		}
		return filtered;
	}

	public void saveMaximumProjections( RandomAccessibleInterval transformedDapiView, String name )
	{
		for ( int d = 0; d < 3; ++d )
		{
			final String title = name + "_view" + d;

			final String path = settings.outputDirectory.getAbsolutePath()
					+ File.separator
					+ settings.inputDataSetName
					+ File.separator
					+ title + ".tiff";

			new File(path).getParentFile().mkdirs();

			IJ.saveAsTiff( ImageJFunctions.wrap( new Projection( transformedDapiView, d ).maximum(), title ), path );
		}
	}

	public void saveImagePlus( ImagePlus imagePlus )
	{

		final String path = settings.outputDirectory.getAbsolutePath()
					+ File.separator
					+ settings.inputDataSetName
					+ File.separator
					+ imagePlus.getTitle() + ".tiff";

		new File( path ).getParentFile().mkdirs();

		Utils.log( "Saving: " + path );
		IJ.saveAsTiff( imagePlus, path );

	}




	public void addSpindlePolesAsInterestPoints( AffineTransform3D alignmentTransform, RandomAccessibleInterval< T > interestPoints, double[] maxLocs )
	{
		double[] spindlePole = new double[ 3 ];
		spindlePole = new double[]{ 0.0, 0.0, maxLocs[ 0 ] };
		convertToOriginalImagePixelUnits( alignmentTransform, spindlePole );
		drawPoint( interestPoints, spindlePole, settings.interestPointsRadius, settings.workingVoxelSize );

		spindlePole = new double[]{ 0.0, 0.0, maxLocs[ 1 ] };
		convertToOriginalImagePixelUnits( alignmentTransform, spindlePole );
		drawPoint( interestPoints, spindlePole, settings.interestPointsRadius, settings.workingVoxelSize );
	}

	public void convertToOriginalImagePixelUnits( AffineTransform3D alignmentTransform, double[] spindlePole )
	{
		Utils.divide( spindlePole, settings.workingVoxelSize );
		alignmentTransform.inverse().apply( spindlePole, spindlePole );
	}

	public void drawPoint( RandomAccessibleInterval< T > rai, double[] position, double radius, double calibration )
	{
		Shape shape = new HyperSphereShape( (int) Math.ceil( radius / calibration ) );
		final RandomAccessible< Neighborhood< T > > nra = shape.neighborhoodsRandomAccessible( rai );
		final RandomAccess< Neighborhood< T > > neighborhoodRandomAccess = nra.randomAccess();

		neighborhoodRandomAccess.setPosition( Utils.asLongs( position )  );
		final Neighborhood< T > neighborhood = neighborhoodRandomAccess.get();

		final Cursor< T > cursor = neighborhood.cursor();
		while( cursor.hasNext() )
		{
			try
			{
				cursor.next().setReal( 200 );
			}
			catch ( ArrayIndexOutOfBoundsException e )
			{
				Utils.log( "[ERROR] Draw points out of bounds..." );
				break;
			}
		}
	}

	public static double[] getLeftAndRightMaxLocs( CoordinatesAndValues tubulinProfile, ArrayList< Double > tubulinProfileAbsoluteDerivative )
	{
		double[] rangeMinMax = new double[ 2 ];
		double[] maxLocs = new double[ 2 ];

		// left
		rangeMinMax[ 0 ] = - Double.MAX_VALUE;
		rangeMinMax[ 1 ] = 0;
		maxLocs[ 1 ] = Utils.computeMaxLoc( tubulinProfile.coordinates, tubulinProfileAbsoluteDerivative, rangeMinMax );

		// right
		rangeMinMax[ 0 ] = 0;
		rangeMinMax[ 1 ] = Double.MAX_VALUE;
		maxLocs[ 0 ] = Utils.computeMaxLoc( tubulinProfile.coordinates, tubulinProfileAbsoluteDerivative, rangeMinMax );

		return maxLocs;
	}

	public static double[] getLeftMaxAndRightMinLoc( ArrayList< Double > coordinates, ArrayList< Double > derivative )
	{
		double[] rangeMinMax = new double[ 2 ];
		double[] maxLocs = new double[ 2 ];

		// left
		rangeMinMax[ 0 ] = - Double.MAX_VALUE;
		rangeMinMax[ 1 ] = 0;
		maxLocs[ 1 ] = Utils.computeMaxLoc( coordinates, derivative, rangeMinMax );

		// right
		rangeMinMax[ 0 ] = 0;
		rangeMinMax[ 1 ] = Double.MAX_VALUE;
		maxLocs[ 0 ] = Utils.computeMinLoc( coordinates, derivative, rangeMinMax );


		return maxLocs;
	}

	public < T extends RealType< T > & NativeType< T > >
	RandomAccessibleInterval< BitType > createMask( RandomAccessibleInterval< T > downscaled, double threshold )
	{
		Utils.log( "Creating mask...");

		RandomAccessibleInterval< BitType > mask = Converters.convert( downscaled, ( i, o ) -> o.set( i.getRealDouble() > threshold ? true : false ), new BitType() );

		mask = opService.morphology().fillHoles( mask );

		return mask;
	}



}
