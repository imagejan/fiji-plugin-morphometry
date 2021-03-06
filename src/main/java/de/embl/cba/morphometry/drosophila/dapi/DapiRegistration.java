package de.embl.cba.morphometry.drosophila.dapi;

import de.embl.cba.morphometry.Algorithms;
import de.embl.cba.morphometry.Angles;
import de.embl.cba.morphometry.refractiveindexmismatch.RefractiveIndexMismatchCorrectionSettings;
import de.embl.cba.morphometry.refractiveindexmismatch.RefractiveIndexMismatchCorrections;
import de.embl.cba.morphometry.Utils;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidsMLJ;
import de.embl.cba.morphometry.geometry.ellipsoids.EllipsoidMLJ;
import de.embl.cba.transforms.utils.Transforms;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;

import java.util.Arrays;
import java.util.List;

import static de.embl.cba.morphometry.Constants.*;
import static de.embl.cba.morphometry.Utils.copyAsArrayImg;
import static de.embl.cba.morphometry.viewing.BdvViewer.show;
import static de.embl.cba.morphometry.viewing.BdvViewer.show;
import static java.lang.Math.*;

public class DapiRegistration
{
	final DapiRegistrationSettings settings;

	public DapiRegistration( DapiRegistrationSettings settings )
	{
		this.settings = settings;
	}

	public < T extends RealType< T > & NativeType< T > >
	AffineTransform3D computeRegistration( RandomAccessibleInterval< T > input, double[] calibration  )
	{

		AffineTransform3D registration = new AffineTransform3D();

		if ( settings.showIntermediateResults ) show( input, "image input data", null, calibration, false );

		calibration = RefractiveIndexMismatchCorrections.getAxiallyCorrectedCalibration( calibration, settings.refractiveIndexCorrectionAxialScalingFactor );

		if ( settings.showIntermediateResults ) show( input, "calibration corrected createTransformedView on raw input data", null, calibration, false );

		AffineTransform3D scalingTransform3D = new AffineTransform3D();
		scalingTransform3D.scale( 0.5 );

		AffineTransform3D scalingToRegistrationResolution = Transforms.getTransformToIsotropicRegistrationResolution( settings.resolutionDuringRegistrationInMicrometer, calibration );

		final RandomAccessibleInterval< T > binnedView = Transforms.createTransformedView( input, scalingToRegistrationResolution );

		final RandomAccessibleInterval< T > binned = copyAsArrayImg( binnedView );

		calibration = getIsotropicCalibration( settings.resolutionDuringRegistrationInMicrometer );

		if ( settings.showIntermediateResults ) show( binned, "binned copyAsArrayImg ( " + settings.resolutionDuringRegistrationInMicrometer + " um )", null, calibration, false );

		// calibration[ Z ], 0.0D, settings.refractiveIndexIntensityCorrectionDecayLength, calibratedCoverslipPosition
		// TODO!!
		RefractiveIndexMismatchCorrections.correctIntensity( binned, new RefractiveIndexMismatchCorrectionSettings() );

		final RandomAccessibleInterval< BitType > binaryImage = Utils.createBinaryImage( binned, settings.threshold );

		if ( settings.showIntermediateResults ) show( binaryImage, "binary", null, calibration, false );

		final EllipsoidMLJ ellipsoidParameters = EllipsoidsMLJ.computeParametersFromBinaryImage( binaryImage );

		registration.preConcatenate( EllipsoidsMLJ.createAlignmentTransform( ellipsoidParameters ) );

		if ( settings.showIntermediateResults ) show( Transforms.createTransformedView( binned, registration ), "ellipsoid aligned", null, calibration, false );

		registration.preConcatenate( Utils.createOrientationTransformation(
				Transforms.createTransformedView( binned, registration ), X,
				settings.derivativeDeltaInMicrometer,
				settings.resolutionDuringRegistrationInMicrometer,
				settings.showIntermediateResults ) );

		if ( settings.showIntermediateResults ) show( Transforms.createTransformedView( binned, registration ), "oriented", null, calibration, false );

		final RandomAccessibleInterval< T > longAxisProjection = Utils.createAverageProjectionAlongAxis(
				Transforms.createTransformedView( binned, registration ), X,
				settings.projectionRangeMinDistanceToCenterInMicrometer,
				settings.projectionRangeMaxDistanceToCenterInMicrometer,
				settings.resolutionDuringRegistrationInMicrometer);

		if ( settings.showIntermediateResults ) show( longAxisProjection, "perpendicular projection", null, new double[]{ calibration[ Y ], calibration[ Z ] }, false );

		final RandomAccessibleInterval< T > blurred = Utils.createBlurredRai(
				longAxisProjection,
				settings.sigmaForBlurringAverageProjectionInMicrometer,
				settings.resolutionDuringRegistrationInMicrometer );

		final Point maximum = Algorithms.getMaximumLocation( blurred, new double[]{ calibration[ Y ], calibration[ Z ] });
		final List< RealPoint > realPoints = Utils.asRealPointList( maximum );
		realPoints.add( new RealPoint( new double[]{ 0, 0 } ) );

		if ( settings.showIntermediateResults ) show( blurred, "perpendicular projection - blurred ", realPoints, new double[]{ calibration[ Y ], calibration[ Z ] }, false );

		registration.preConcatenate( createXAxisRollTransform( maximum ) );

		if ( settings.showIntermediateResults ) show( Transforms.createTransformedView( binned, registration ), "registered binned image", null, calibration, false );

		final AffineTransform3D scalingToFinalResolution = Transforms.getTransformToIsotropicRegistrationResolution( settings.finalResolutionInMicrometer / settings.resolutionDuringRegistrationInMicrometer, new double[]{ 1, 1, 1 } );

		registration = scalingToRegistrationResolution.preConcatenate( registration ).preConcatenate( scalingToFinalResolution );

		return registration;

	}

	public static double[] getIsotropicCalibration( double value )
	{
		double[] calibration = new double[ 3 ];

		Arrays.fill( calibration, value );

		return calibration;
	}

	public static < T extends RealType< T > & NativeType< T > >
	AffineTransform3D createXAxisRollTransform( Point maximum2DinYZPlane )
	{
		double angleToZAxisInDegrees = Angles.angle2DToCoordinateSystemsAxisInDegrees( maximum2DinYZPlane );
		AffineTransform3D rollTransform = new AffineTransform3D();

		Utils.log( "Roll angle: " + angleToZAxisInDegrees );
		rollTransform.rotate( X, toRadians( angleToZAxisInDegrees ) );

		return rollTransform;
	}


}
