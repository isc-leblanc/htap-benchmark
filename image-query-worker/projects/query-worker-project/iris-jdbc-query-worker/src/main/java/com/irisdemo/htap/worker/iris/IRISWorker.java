package com.irisdemo.htap.worker.iris;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.irisdemo.htap.config.Config;

import com.irisdemo.htap.workersrv.WorkerSemaphore;
import com.irisdemo.htap.workersrv.AccumulatedMetrics;
import com.irisdemo.htap.workersrv.IWorker;

@Component("worker")
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class IRISWorker implements IWorker 
{
    Logger logger = LoggerFactory.getLogger(IRISWorker.class);

    @Autowired
    WorkerSemaphore workerSemaphore;
    
    @Autowired 
    AccumulatedMetrics accumulatedMetrics;
    
    @Autowired
    Config config;    

    DriverManagerDataSource dataSourceCache;

	/**
	 * I could not make this a Bean. It would get created before we had fetched the connection information from
	 * the master.
	 * 
	 * @return
	 * @throws SQLException
	 * @throws IOException
	 */
	synchronized public DriverManagerDataSource getDataSource() throws SQLException, IOException
	{
		if (dataSourceCache==null)
		{
	        logger.info("Creating data source for '" + config.getConsumptionJDBCURL() + "'...");
	        Properties connectionProperties = new Properties();
	        connectionProperties.setProperty("user", config.getConsumptionJDBCUserName());
	        connectionProperties.setProperty("password", config.getConsumptionJDBCPassword());
	
	        dataSourceCache = new DriverManagerDataSource(config.getConsumptionJDBCURL(), connectionProperties);
		}
        
        return dataSourceCache;
    }

	@Async("workerExecutor")
    public CompletableFuture<Long> startOneConsumer(int threadNum) throws IOException, SQLException
    {	
		PreparedStatement preparedStatement;
		ResultSet rs;
		ResultSetMetaData rsmd;
		Connection connection = getDataSource().getConnection();
		double t0, t1, rowCount;
		int idIndex, rowSizeInBytes, colnumCount =0;
		
		logger.info("Starting Consumer thread "+threadNum+"...");
		accumulatedMetrics.incrementNumberOfActiveQueryThreads();
		
		String[] IDs = new String[config.getConsumptionNumOfKeysToFetch()];

		for (idIndex = 0; idIndex<config.getConsumptionNumOfKeysToFetch(); idIndex++)
		{
			IDs[idIndex]="W1A"+idIndex;
		}		 	 
		
		/*
		 *  Each thread will run queries on the first 4 elements of the array.
		 *  Harry randomly shuffled the array so that each thread would be fetching different records.
		 */

		Random rnd = ThreadLocalRandom.current();
		
		for (idIndex = IDs.length - 1; idIndex > 0; idIndex--)
		{
			int index = rnd.nextInt(idIndex + 1);
		    // Simple swap
		    String id = IDs[index];
		    IDs[index] = IDs[idIndex];
		    IDs[idIndex] = id;
		}
		
		try 
		{
			preparedStatement = connection.prepareStatement(config.getQueryByIdStatement());
			
			// Run one query just to know how many columns are going to come
			preparedStatement.setString(1, IDs[0]);
			rs = preparedStatement.executeQuery();
			rs.next(); // It doesn't atter if we have a record or not. We just want the metadata
			rsmd = rs.getMetaData();
			colnumCount = rsmd.getColumnCount();
			// Now colnumCount has a value
	
			while(workerSemaphore.green())
			{

				for (idIndex = 0; idIndex<IDs.length; idIndex++)
				{		
					t0 = System.currentTimeMillis();
					rowCount=0;
					rowSizeInBytes=0;
				
					preparedStatement.setString(1, IDs[idIndex]);
					rs = preparedStatement.executeQuery();
										
					/* 
					 * The customer said that it is not fair if we just read the data and
					 * don't do anything with it. So we will just compute the approximate size of
					 * the data we have read to show "proof of work".
					 */					
	                if (rs.next()) 
	                {
	                	rowCount++;
	                    for (int column=1; column<=colnumCount; column++) 
	                    {
	                    	// Approximate size
	                    	rowSizeInBytes += rs.getString(column).getBytes().length;
	                    }
	                 }
					 
					 t1= System.currentTimeMillis(); 
					 accumulatedMetrics.addToStats(t1-t0, rowCount, rowSizeInBytes);
	 
				}

		   
				if (config.getConsumptionTimeBetweenQueriesInMillis()>0)
				{
					Thread.sleep(config.getConsumptionTimeBetweenQueriesInMillis());
				}
			}
		} 
		catch (SQLException sqlException) 
		{
			logger.error("Exception while running consumer query: " + sqlException.getMessage());
			System.exit(-1);
		} 
		catch (InterruptedException e) 
		{
			logger.warn("Thread has been interrupted. Maybe the master asked it to stop: " + e.getMessage());
		} 
		finally
		{
			connection.close();
		}
		
		accumulatedMetrics.decrementNumberOfActiveQueryThreads();

		return null;
	}
	
}
