/***********************************************************************
 * Copyright (c) 2004, 2007 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Actuate Corporation - initial API and implementation
 ***********************************************************************/

package org.eclipse.birt.report.engine.layout.html;

import org.eclipse.birt.report.engine.content.IContent;
import org.eclipse.birt.report.engine.extension.IReportItemExecutor;
import org.eclipse.birt.report.engine.layout.ILayoutManager;

public class HTMLBlockStackingLM extends HTMLStackingLM
{

	ILayoutManager childLayout;
	IReportItemExecutor childExecutor;

	public HTMLBlockStackingLM( HTMLLayoutManagerFactory factory )
	{
		super( factory );
	}

	public int getType( )
	{
		return LAYOUT_MANAGER_BLOCK;
	}

	protected boolean isChildrenFinished()
	{
		return childExecutor == null && !executor.hasNextChild( );
	}
	
	protected boolean layoutNodes( )
	{
		boolean hasNext = false;
		
		// first we need layout the remain content
		if ( childLayout != null )
		{
			hasNext = childLayout.layout( );
			if (childLayout.isFinished( ))
			{
				childLayout.close( );
				childExecutor.close( );
				childLayout = null;
				childExecutor = null;
			}
			if ( hasNext )
			{
				return true;
			}
		}
		// then layout the next content
		while ( executor.hasNextChild( ) && !context.getCancelFlag( ) )
		{
			childExecutor = (IReportItemExecutor) executor.getNextChild( );
			IContent childContent = childExecutor.execute( );
			if ( childContent != null )
			{
				childLayout = engine.createLayoutManager( this, childContent,
						childExecutor, emitter );
				hasNext = childLayout.layout( );
				if ( hasNext )
				{
					if ( childLayout.isFinished( ) )
					{
						childLayout.close( );
						childExecutor.close( );
						childLayout = null;
						childExecutor = null;
					}
					return true;
				}
				childLayout.close( );
				childLayout = null;
			}
			childExecutor.close( );
			childExecutor = null;
		}
		return false;
	}
}
