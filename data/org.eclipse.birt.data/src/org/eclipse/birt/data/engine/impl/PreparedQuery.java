/*
 *************************************************************************
 * Copyright (c) 2004, 2005 Actuate Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Actuate Corporation  - initial API and implementation
 *  
 *************************************************************************
 */ 

package org.eclipse.birt.data.engine.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.birt.core.data.ExpressionUtil;
import org.eclipse.birt.data.engine.api.DataEngineContext;
import org.eclipse.birt.data.engine.api.IBaseExpression;
import org.eclipse.birt.data.engine.api.IBaseQueryDefinition;
import org.eclipse.birt.data.engine.api.IBaseTransform;
import org.eclipse.birt.data.engine.api.IBinding;
import org.eclipse.birt.data.engine.api.IExpressionCollection;
import org.eclipse.birt.data.engine.api.IConditionalExpression;
import org.eclipse.birt.data.engine.api.IFilterDefinition;
import org.eclipse.birt.data.engine.api.IGroupDefinition;
import org.eclipse.birt.data.engine.api.IQueryResults;
import org.eclipse.birt.data.engine.api.IScriptExpression;
import org.eclipse.birt.data.engine.api.ISubqueryDefinition;
import org.eclipse.birt.data.engine.api.querydefn.Binding;
import org.eclipse.birt.data.engine.api.querydefn.ConditionalExpression;
import org.eclipse.birt.data.engine.api.querydefn.GroupDefinition;
import org.eclipse.birt.data.engine.api.querydefn.ScriptExpression;
import org.eclipse.birt.data.engine.api.querydefn.SortDefinition;
import org.eclipse.birt.data.engine.api.querydefn.SubqueryDefinition;
import org.eclipse.birt.data.engine.core.DataException;
import org.eclipse.birt.data.engine.expression.CompiledExpression;
import org.eclipse.birt.data.engine.expression.ExpressionCompiler;
import org.eclipse.birt.data.engine.i18n.ResourceConstants;
import org.eclipse.birt.data.engine.impl.aggregation.AggregateRegistry;
import org.eclipse.birt.data.engine.impl.aggregation.AggregateTable;
import org.eclipse.birt.data.engine.impl.document.QueryResultIDUtil;
import org.eclipse.birt.data.engine.odi.IResultIterator;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/** 
 * Two main functions for PreparedDataSourceQuery or PreparedSubQuery:
 * 		1: prepare group, subquery and expressions
 * 		2: query preparation and sub query execution
 */
final class PreparedQuery 
{
	private 	IBaseQueryDefinition 	baseQueryDefn;
	
	private     DataEngineContext 		dataEngineContext;
	private 	DataEngineSession 		session;
//	private     Scriptable 				sharedScope;
	private     ExpressionCompiler 		expressionCompiler;
	private 	IPreparedQueryService 	queryService;
	
	private  	AggregateTable			aggrTable;
	private 	Map 					appContext;
	
	// Map of Subquery name (String) to PreparedSubquery
	private 	HashMap 				subQueryMap;
	
	private 	HashMap 				subQueryDefnMap;
	
	private 	static Logger logger = Logger.getLogger( DataEngineImpl.class.getName( ) );
	
	private 	ExprManager 			exprManager;
	
	/**
	 * @param deContext
	 * @param scope
	 * @param queryDefn
	 * @param queryService
	 * @param appContext
	 * @throws DataException
	 */
	PreparedQuery( DataEngineSession session, DataEngineContext deContext,
			IBaseQueryDefinition queryDefn, IPreparedQueryService queryService,
			Map appContext ) throws DataException
	{
		logger.logp( Level.FINE,
				PreparedQuery.class.getName( ),
				"PreparedQuery",
				"PreparedQuery starts up." );
		assert queryDefn != null;

		this.expressionCompiler = new ExpressionCompiler( );
		this.expressionCompiler.setDataSetMode( false );
		this.dataEngineContext = deContext;
		this.session = session;
		this.baseQueryDefn = QueryDefinitionCopyUtil.copy( queryDefn );
		this.queryService = queryService;
		this.appContext = appContext;
		
		this.exprManager = new ExprManager( baseQueryDefn );
		this.subQueryMap = new HashMap( );
		this.subQueryDefnMap = new HashMap( );
		this.aggrTable = new AggregateTable( this.session.getSharedScope( ),
				baseQueryDefn.getGroups( ) );

		logger.fine( "Start to prepare a PreparedQuery." );
		prepare( );
		logger.fine( "Finished preparing the PreparedQuery." );
	}
	
	/**
	 * @throws DataException
	 */
	private void prepare( ) throws DataException
	{
	    // TODO - validation of static queryDefn

		Context cx = Context.enter();		
		try
		{
			// Prepare all groups; note that the report query iteself
			// is treated as a group (with group level 0 ), If there are group
			// definitions that of invalid or duplicate group name, then throw
			// exceptions.			
			if ( this.baseQueryDefn.getBindings( ) != null
					&& this.baseQueryDefn.getBindings( ).size( ) > 0 )
			{
				this.expressionCompiler.setDataSetMode( false );
			}
			
			List groups = baseQueryDefn.getGroups( );
			Set groupNameSet = new HashSet( );
			IGroupDefinition group;
			for ( int i = 0; i < groups.size( ); i++ )
			{
				group = (IGroupDefinition) groups.get( i );
				if ( group.getName( ) == null
						|| group.getName( ).trim( ).length( ) == 0 )
					continue;
				for ( int j = 0; j < groups.size( ); j++ )
				{
					if ( group.getName( )
							.equals( ( (IGroupDefinition) groups.get( j ) ).getName( ) == null
									? ""
									: ( (IGroupDefinition) groups.get( j ) ).getName( ) )
							&& j != i )
						throw new DataException( ResourceConstants.DUPLICATE_GROUP_NAME );
				}
				groupNameSet.add( group.getName( ) );
			}

			// The latest column binding (AggregateOn introduced)
			Map map = baseQueryDefn.getBindings( );
			if ( map != null )
			{
				Iterator it = map.keySet( ).iterator( );
				while ( it.hasNext( ) )
				{
					Object key = it.next( );
					IBinding binding = (IBinding)map.get( key );
					String groupName = null;
					if ( binding.getExpression( )!= null )
						groupName = binding.getExpression( ).getGroupName( );
					
					if( groupName == null )
					{
						if ( binding.getAggregatOns( ).size( ) == 0 )
							continue;
						groupName = binding.getAggregatOns( ).get( 0 ).toString( );
					}
					
					if ( ( !groupName
							.equals( IBaseExpression.GROUP_OVERALL ) )
							&& !groupNameSet.contains( groupName ) )
					{
						throw new DataException( ResourceConstants.GROUP_NOT_EXIST,
								new Object[]{
								groupName, key
								} );
					}
				}
			}

			mappingParentColumnBinding( );
			
			for ( int i = 0; i <= groups.size( ); i++ )
			{
				prepareGroup( baseQueryDefn, i, cx );
			}
		}
		finally
		{			
		    Context.exit();
		}
	}
	
	/**
	 * @throws DataException 
	 * 
	 */
	private void mappingParentColumnBinding( ) throws DataException
	{
		IBaseQueryDefinition queryDef =  baseQueryDefn;
		while ( queryDef instanceof ISubqueryDefinition )
		{
			queryDef = queryDef.getParentQuery();
			Map parentBindings = queryDef.getBindings( );
			addParentBindings(parentBindings);
		}
	}

	/**
	 * 
	 * @param parentBindings
	 * @throws DataException 
	 */
	private void addParentBindings( Map parentBindings ) throws DataException {
		Iterator it = parentBindings.keySet( ).iterator( );
		while ( it.hasNext( ) )
		{
			Object o = it.next( );
			IBaseExpression expr = ((IBinding)parentBindings.get( o )).getExpression( );
			if ( expr instanceof IScriptExpression )
			{
				if (!ExpressionUtil.hasAggregation( ( (IScriptExpression) expr ).getText( ) ))
				{
					if ( baseQueryDefn.getBindings( )
							.get( o ) == null )
					{	
						IBinding binding = new Binding( o.toString( ) );
						binding.setDataType( expr.getDataType( ) );
						binding.setExpression( copyScriptExpr( expr ) );
						baseQueryDefn.addBinding( binding );
					}
				}
			}
		}
	}

	/**
	 * Colon a script expression, however do not populate the "AggregateOn" field. All the column binding that inherit
	 * from parent query by sub query should have no "AggregateOn" field, for they could not be aggregations. However, 
	 * if an aggregateOn field is set to an expression without aggregation, we should also make it inheritable by sub query
	 * for the expression actually involves no aggregations.
	 * 
	 * @param expr
	 * @return
	 */
	private ScriptExpression copyScriptExpr( IBaseExpression expr )
	{
		ScriptExpression se = new ScriptExpression( ( (IScriptExpression) expr ).getText( ),
				( (IScriptExpression) expr ).getDataType( ) );
		return se;
	}
	
	/**
	 * @param trans
	 * @param groupLevel
	 * @param cx
	 * @throws DataException
	 */
	private void prepareGroup( IBaseQueryDefinition baseQuery, int groupLevel,
			Context cx ) throws DataException
	{
		IBaseTransform trans = baseQuery;
		String groupName = IBaseExpression.GROUP_OVERALL;
			
		// Group 0
		if ( groupLevel != 0 )
		{	
			IGroupDefinition igd = (IGroupDefinition) ( (IBaseQueryDefinition) trans ).getGroups( )
					.get( groupLevel - 1 );
			trans = igd;
			groupName = igd.getName();
		}		
		
		Collection exprCol = new ArrayList( );
		Map resultSetExpressions = new HashMap();
		
		//The latest column binding (AggregateOn introduced) 
		Map map = baseQuery.getBindings( );
		if (map != null) 
		{
			Iterator it = map.keySet().iterator();
			while (it.hasNext()) {
				Object key = it.next();
				IBinding icbe = ((IBinding)map.get(key));
				if ( icbe.getExpression( ) != null && icbe.getExpression( )
								.getGroupName( )
								.equals( groupName ) && groupLevel!= 0 )
				{
					exprCol.add( icbe.getExpression( ) );
					resultSetExpressions.put(key, icbe);
				}else if ( groupLevel == 0 && icbe.getAggregatOns( ).size( ) == 0 )
				{
					exprCol.add( icbe.getExpression( ) );
					resultSetExpressions.put(key, icbe);
				}else if ( groupLevel!= 0 && icbe.getAggregatOns( ).contains( groupName ))
				{
					exprCol.add( icbe.getExpression( ) );
					resultSetExpressions.put(key, icbe);
				}	
			}
		}

		prepareExpressions(exprCol, groupLevel, false, true, cx);
		
		String key = null;
		if( trans instanceof IGroupDefinition )
		{
			IGroupDefinition gd = (IGroupDefinition)trans;
			key = gd.getKeyColumn( ) != null ? gd.getKeyColumn( ):gd.getKeyExpression( );
		}
		this.exprManager.addBindingExpr( key, resultSetExpressions, groupLevel );
		
		// Prepare subqueries appearing in this group
		Collection subQueries = trans.getSubqueries( );
		Iterator subIt = subQueries.iterator( );
		while ( subIt.hasNext( ) )
		{
			ISubqueryDefinition subquery = (ISubqueryDefinition) subIt.next( );
			PreparedSubquery pq = new PreparedSubquery( this.session, this.dataEngineContext,
					subquery,
					queryService,
					groupLevel );
			subQueryMap.put( subquery.getName(), pq);
			
			subQueryDefnMap.put( subquery.getName( ), new Object[]{
					subquery, new Integer( groupLevel )
			} );
		}
	}
	
	/**
	 * Prepares all expressions in the given collection
	 * 
	 * @param expressions
	 * @param groupLevel
	 * @param afterGroup
	 * @param cx
	 * @throws DataException 
	 */
	private void prepareExpressions( Collection expressions, int groupLevel,
			boolean afterGroup, boolean isDetailedRow, Context cx ) throws DataException
	{
	    if ( expressions == null )
	        return;
	    
	    AggregateRegistry reg = this.aggrTable.getAggrRegistry( groupLevel,
	    		-1,
				isDetailedRow,
				cx );
	    Iterator it = expressions.iterator();
	    while ( it.hasNext() )
	    {
	        prepareExpression((IBaseExpression) it.next(), groupLevel, cx, reg);
	    }
	}
	
	/**
	 * Prepares one expression
	 * 
	 * @param expr
	 * @param groupLevel
	 * @param cx
	 * @param reg
	 */
	private void prepareExpression( IBaseExpression expr, int groupLevel,
			Context cx, AggregateRegistry reg )
	{
	    ExpressionCompiler compiler = this.expressionCompiler;
	    
	    if ( expr instanceof IScriptExpression )
	    {
	    	String exprText = ((IScriptExpression) expr).getText();
	    	CompiledExpression handle = compiler.compile( exprText, reg, cx);
	    	expr.setHandle( handle );
	    }
	    else if ( expr instanceof IConditionalExpression )
	    {
	    	// 3 sub expressions of the conditional expression should be prepared
	    	// individually
	    	IConditionalExpression ce = (IConditionalExpression) expr;
	    	ce = transformConditionalExpression( ce );
			
	    	prepareExpression( ce.getExpression(), groupLevel, cx, reg );
	    	if ( ce.getOperand1() != null )
		    	prepareExpression( ce.getOperand1(), groupLevel, cx, reg );
	    	if ( ce.getOperand2() != null )
		    	prepareExpression( ce.getOperand2(), groupLevel, cx, reg );

	    	// No separate preparation is required for the conditional expression 
	    	// Set itself as the compiled handle
	    	expr.setHandle( ce );
	    }
	    else if ( expr instanceof IExpressionCollection )
		{
			IExpressionCollection ce = (IExpressionCollection) expr;
			Object[] exprs = ce.getExpressions( ).toArray( );
			for ( int i = 0; i < exprs.length; i++ )
			{
				prepareExpression( (IBaseExpression)exprs[i],
						groupLevel,
						cx,
						reg );
			}
		}
	    else
	    {
	    	// Should never get here
	    	assert false;
	    }
	}

	/**
	 * When a TopN/TopPercent/BottomN/BottomPercent ConditionalExpression is
	 * set, transform it to
	 * Total.TopN/Total.TopPercent/Total.BottomN/Total.BottomPercent
	 * aggregations with "isTrue" operator.
	 * 
	 * @param ce
	 * @return
	 */
	private IConditionalExpression transformConditionalExpression(
			IConditionalExpression ce )
	{
		String prefix = null;
		
		switch ( ce.getOperator( ) )
		{
			case IConditionalExpression.OP_TOP_N :
				prefix = "Total.isTopN";
				break;
			case IConditionalExpression.OP_TOP_PERCENT :
				prefix = "Total.isTopNPercent";
				break;
			case IConditionalExpression.OP_BOTTOM_N :
				prefix = "Total.isBottomN";
				break;
			case IConditionalExpression.OP_BOTTOM_PERCENT :
				prefix = "Total.isBottomNPercent";
				break;
		}
		
		if( prefix != null )
		{
			ce = new ConditionalExpression( prefix +
					"(" + ce.getExpression( ).getText( ) + "," +
					( (IScriptExpression) ce.getOperand1( ) ).getText( ) + ")",
					IConditionalExpression.OP_TRUE );
		}
		return ce;
	}
	
	/**
	 * Return the QueryResults. But the execution of query would be deferred
	 * 
	 * @param outerResults
	 *            If query is nested within another query, this is the outer
	 *            query's query result handle.
	 * @param scope
	 *            The ElementState object for the report item using the query;
	 *            this acts as the JS scope for evaluating script expressions.
	 * @param executor
	 * @parem dataSourceQuery
	 */
	QueryResults doPrepare( IQueryResults outerResults,
			Scriptable scope, QueryExecutor executor,
			PreparedDataSourceQuery dataSourceQuery ) throws DataException
	{
		if ( this.baseQueryDefn == null )
		{
			// we are closed
			DataException e = new DataException(ResourceConstants.PREPARED_QUERY_CLOSED);
			logger.logp( Level.WARNING,
					PreparedQuery.class.getName( ),
					"doPrepare",
					"PreparedQuery instance is closed.",
					e );
			throw e;
		}
		
		// pass the prepared query's pass thru context to its executor
		executor.setAppContext( this.appContext );
		
		//here prepare the execution. After the preparation the result metadata is available by
		//calling getResultClass, and the query is ready for execution.
		logger.finer( "Start to prepare the execution." );
		executor.prepareExecution( outerResults, scope );
		logger.finer( "Finish preparing the execution." );
		QueryResults result = new QueryResults( new ServiceForQueryResults( this.dataEngineContext,
				executor.getQueryScope( ),
				executor.getNestedLevel( ) + 1,
				dataSourceQuery,
				queryService,
				executor,
				this.baseQueryDefn,
				this.exprManager ) );
		if( this.baseQueryDefn.cacheQueryResults() )
		{
			result.setID( QueryResultIDUtil.nextID( ) );
		}
		return result;
	}
	
	/**
	 * @param subQueryName
	 * @return
	 */
	ISubqueryDefinition getSubQueryDefn( String subQueryName )
	{
		return (ISubqueryDefinition) ( (Object[]) subQueryDefnMap.get( subQueryName ) )[0];
	}
	
	/**
	 * @param subQueryName
	 * @return
	 */
	int getSubQueryLevel( String subQueryName )
	{
		return ( (Integer) ( (Object[]) subQueryDefnMap.get( subQueryName ) )[1] ).intValue( );
	}
	
	/**
	 * Executes a subquery
	 * 
	 * @param iterator
	 * @param subQueryName
	 * @param subScope
	 * @return
	 * @throws DataException
	 */
	QueryResults execSubquery( IResultIterator iterator, IQueryExecutor executor, String subQueryName,
			Scriptable subScope ) throws DataException
	{
		assert subQueryName != null;

		PreparedSubquery subquery = (PreparedSubquery) subQueryMap.get( subQueryName );
		if ( subquery == null )
		{
			DataException e = new DataException( ResourceConstants.SUBQUERY_NOT_FOUND,
					subQueryName );
			logger.logp( Level.FINE,
					PreparedQuery.class.getName( ),
					"execSubquery",
					"Subquery name not found",
					e );
			throw e;
		}

		return subquery.execute( iterator, executor, subScope );
	}
	
	/**
	 * Closes the prepared query. This instance can no longer be executed after
	 * it is closed 
	 * 
	 * TODO: expose this method in the IPreparedQuery interface
	 */
	void close( )
	{
		this.baseQueryDefn = null;
		this.aggrTable = null;
		this.subQueryMap = null;
		
		logger.logp( Level.FINER,
				PreparedQuery.class.getName( ),
				"close",
				"Prepared query closed" );
		
		// TODO: close all open QueryResults obtained from this PreparedQuery
	}
	
	
	/**
	 * @return sharedScope
	 */
	Scriptable getSharedScope( )
	{
		return this.session.getSharedScope( );
	}

	/**
	 * @return baseQueryDefinition
	 */
	IBaseQueryDefinition getBaseQueryDefn( )
	{
		return baseQueryDefn;
	}
	
	/**
	 * @return aggregateTable
	 */
	AggregateTable getAggrTable( )
	{
		return aggrTable;
	}
	
}

/**
 * 
 *
 */
class QueryDefinitionCopyUtil
{
	/**
	 * 
	 * @param baseQyeryDef
	 * @return
	 * @throws DataException 
	 */
	static IBaseQueryDefinition copy( IBaseQueryDefinition baseQyeryDef ) throws DataException
	{
		if( baseQyeryDef instanceof SubqueryDefinition )
		{
			SubqueryDefinition srcSubQueryDefn = (SubqueryDefinition) baseQyeryDef;
			SubqueryDefinition destSubQueryDefn = new SubqueryDefinition(
					srcSubQueryDefn.getName( ), baseQyeryDef
							.getParentQuery( ) );
			destSubQueryDefn.setApplyOnGroupFlag( srcSubQueryDefn.applyOnGroup( ) );
			destSubQueryDefn.setMaxRows( srcSubQueryDefn.getMaxRows( ) );
			destSubQueryDefn.setUsesDetails( srcSubQueryDefn.usesDetails( ) );
			copyGroupList( srcSubQueryDefn, destSubQueryDefn );
			copyExpressions( srcSubQueryDefn, destSubQueryDefn );
			copySubQueryList( srcSubQueryDefn, destSubQueryDefn );
			copySortList( srcSubQueryDefn, destSubQueryDefn );
			copyFilterList( srcSubQueryDefn, destSubQueryDefn );
			return destSubQueryDefn;
		}
		else
		{
			return baseQyeryDef;
		}
	}

	/**
	 * 
	 * @param srcSubQueryDefn
	 * @param destSubQueryDefn
	 * @throws DataException 
	 */
	private static void copyExpressions(
			SubqueryDefinition srcSubQueryDefn,
			SubqueryDefinition destSubQueryDefn) throws DataException 
	{
		Map bindings = srcSubQueryDefn.getBindings( );
		Iterator it = bindings.keySet( ).iterator( );
		while ( it.hasNext( ) )
		{
			Object o = it.next( );
			destSubQueryDefn.addBinding(convertToBindings( bindings.get( o ) ));
		}
	}

	/**
	 * 
	 * @param bindings
	 * @param o
	 * @return
	 */
	private static IBinding convertToBindings( Object binding )
	{
		if( binding instanceof IBinding )
		{
			return (IBinding)binding;
		}
		else
		{
			return null;
		}
	}

	/**
	 * 
	 * @param srcSubQueryDefn
	 * @param destSubQueryDefn
	 */
	private static void copyGroupList(SubqueryDefinition srcSubQueryDefn,
			SubqueryDefinition destSubQueryDefn) 
	{
		List groupList = srcSubQueryDefn.getGroups( );
		for( int i = 0; i < groupList.size( ); i++ )
		{
			destSubQueryDefn.addGroup( (GroupDefinition) groupList.get( i ) );
		}
	}
	
	/**
	 * 
	 * @param srcSubQueryDefn
	 * @param destSubQueryDefn
	 */
	private static void copySubQueryList(SubqueryDefinition srcSubQueryDefn,
			SubqueryDefinition destSubQueryDefn) 
	{
		Object[] subQueryDefn = srcSubQueryDefn.getSubqueries( ).toArray( );
		for( int i = 0; i < subQueryDefn.length; i++ )
		{
			destSubQueryDefn.addSubquery( (SubqueryDefinition) subQueryDefn[i] );
		}
	}
	
	/**
	 * 
	 * @param srcSubQueryDefn
	 * @param destSubQueryDefn
	 */
	private static void copySortList(SubqueryDefinition srcSubQueryDefn,
			SubqueryDefinition destSubQueryDefn) 
	{
		List sortList = srcSubQueryDefn.getSorts( );
		for( int i = 0; i < sortList.size( ); i++ )
		{
			destSubQueryDefn.addSort( (SortDefinition) sortList.get( i ) );
		}
	}
	
	/**
	 * 
	 * @param srcSubQueryDefn
	 * @param destSubQueryDefn
	 */
	private static void copyFilterList(SubqueryDefinition srcSubQueryDefn,
			SubqueryDefinition destSubQueryDefn) 
	{
		List filterList = srcSubQueryDefn.getFilters( );
		for( int i = 0; i < filterList.size( ); i++ )
		{
			destSubQueryDefn.addFilter( (IFilterDefinition) filterList.get( i ) );
		}
	}
	
}