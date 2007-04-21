/*******************************************************************************
 * Copyright (c) 2004 Actuate Corporation .
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *******************************************************************************/

package org.eclipse.birt.report.designer.internal.ui.layout;

import org.eclipse.birt.report.designer.internal.ui.editors.parts.DeferredGraphicalViewer;
import org.eclipse.birt.report.designer.internal.ui.editors.schematic.figures.ReportElementFigure;
import org.eclipse.draw2d.geometry.Insets;
import org.eclipse.draw2d.geometry.PrecisionDimension;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.EditPartListener;
import org.eclipse.gef.GraphicalEditPart;
import org.eclipse.gef.editparts.ZoomListener;
import org.eclipse.gef.editparts.ZoomManager;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;

/**
 * add comment here
 *  
 */
public abstract class AbstractPageFlowLayout extends ReportFlowLayout
{

	public static final int MINLEFTSPACE = 20;
	public static final int MINRIGHTSPACE = 20;
	public static final int MINTOPSPACE = 20;
	public static final int MINBOTTOMSPACE = 20;
	private GraphicalEditPart owner;

	private Rectangle initSize = new Rectangle( );
	private Insets initInsets = new Insets( );
	private Rectangle comsiteBounds = new Rectangle( );

	/**
	 * @param owner
	 */
	public AbstractPageFlowLayout( final GraphicalEditPart owner )
	{
		super( );
		assert owner != null;
		this.owner = owner;

		final ControlListener listener = new ControlListener( )
		{

			public void controlMoved( ControlEvent e )
			{

			}

			public void controlResized( ControlEvent e )
			{
				setComsiteBounds( new Rectangle( ( getOwner( ).getViewer( )
						.getControl( ).getBounds( ) ) ) );
				layouRootLayer( );
			}

		};
		owner.getViewer( ).getControl( ).addControlListener( listener );
		owner.addEditPartListener( new EditPartListener( )
		{

			public void childAdded( EditPart child, int index )
			{
				//Do nothing
			}

			public void partActivated( EditPart editpart )
			{
				//Do nothing
			}

			public void partDeactivated( EditPart editpart )
			{
				owner.getViewer( ).getControl( ).removeControlListener(
						listener );
			}

			public void removingChild( EditPart child, int index )
			{
				// Do nothing

			}

			public void selectedStateChanged( EditPart editpart )
			{
				//Do nothing

			}

		} );

		getZoomManager( ).addZoomListener( new ZoomListener( )
		{

			public void zoomChanged( double zoom )
			{
				if (getOwner( ).getParent( ) == null)
				{
					return ;
				}
				setComsiteBounds( new Rectangle( ( getOwner( ).getViewer( )
						.getControl( ).getBounds( ) ) ) );
				layouRootLayer( );

			}
		} );

	}

	private void layouRootLayer( )
	{
		if ( getOwner( ).getFigure( ) instanceof ReportElementFigure )
		{
			( (ReportElementFigure) getOwner( ).getFigure( ) ).fireMoved( );
		}
	}

	/**
	 * @return Returns the owner.
	 */
	protected GraphicalEditPart getOwner( )
	{
		return owner;
	}

	/**
	 * @param owner
	 *            The owner to set.
	 */
	protected void setOwner( GraphicalEditPart owner )
	{
		this.owner = owner;
	}

	/**
	 * Returns the zoom manager for current viewer.
	 * 
	 * @return
	 */
	public ZoomManager getZoomManager( )
	{
		return (ZoomManager) owner.getViewer( ).getProperty(
				ZoomManager.class.toString( ) );
	}

	protected static class Result
	{

		public Rectangle reportSize = new Rectangle( );;
		public int rightSpace;
		public int bottomSpace;
	}

	/**
	 * @return Returns the comsiteBounds.
	 */
	public Rectangle getComsiteBounds( )
	{
		return comsiteBounds;
	}

	/**
	 * @param comsiteBounds
	 *            The comsiteBounds to set.
	 */
	public void setComsiteBounds( Rectangle comsiteBounds )
	{
		this.comsiteBounds = comsiteBounds;
		layout( getOwner( ).getFigure( ) );
	}

	/**
	 * @return Returns the initSize.
	 */
	public Rectangle getInitSize( )
	{
		return initSize;
	}

	/**
	 * @param initSize
	 *            The initSize to set.
	 */
	public void setInitSize( Rectangle initSize )
	{
		this.initSize = initSize;
	}

	protected void setViewProperty( Rectangle caleBounds, Rectangle ownerBounds )
	{
		getOwner( ).getViewer( ).setProperty(
				DeferredGraphicalViewer.REPORT_SIZE, caleBounds );
		getOwner( ).getViewer( ).setProperty(
				DeferredGraphicalViewer.LAYOUT_SIZE, ownerBounds );

	}

	protected Result getReportBounds( Rectangle reportSize )
	{
		Result revValue = new Result( );
		revValue.reportSize.y = MINTOPSPACE;
		revValue.reportSize.width = reportSize.width;
		revValue.reportSize.height = reportSize.height;

		PrecisionDimension dim = new PrecisionDimension(
				getComsiteBounds( ).width, getComsiteBounds( ).height );
		double scale = getZoomManager( ).getZoom( );
		dim.performScale( 1 / scale );
		if ( dim.width > reportSize.width + MINLEFTSPACE + MINRIGHTSPACE )
		{
			revValue.reportSize.x = ( dim.width - reportSize.width ) / 2;
			revValue.rightSpace = ( dim.width - reportSize.width ) / 2;
		}
		else
		{
			revValue.reportSize.x = MINLEFTSPACE;
			revValue.rightSpace = MINRIGHTSPACE;
		}

		if ( dim.height > reportSize.height + MINTOPSPACE + MINBOTTOMSPACE )
		{
			revValue.bottomSpace = dim.height - reportSize.height
					- revValue.reportSize.y;
		}
		else
		{
			revValue.bottomSpace = MINBOTTOMSPACE;
		}

		return revValue;
	}

	public Insets getInitInsets() {
		return initInsets;
	}

	public void setInitInsets(Insets initInsets) {
		this.initInsets = initInsets;
	}
}