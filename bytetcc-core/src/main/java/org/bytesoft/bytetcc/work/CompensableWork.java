/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.work;

import javax.resource.spi.work.Work;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 干两个事情的
 * 第一件事情，就是在系统每次刚启动的时候，对执行到一半儿的事务，还没执行结束的事务，进行恢复，继续执行这个分布式事务
 *
 * 第二件事情，如果就像我们现在的这个场景，就是分布式事务中所有服务的try都成功了，然后执行confirm，其他服务的confirm都成功了，
 * 可能就1个服务的confirm失败了；或者其他服务的cancel都成功了，可能就1个cancel没成功，此时CompensableWork会每隔一段时间，定时不断的去重试
 * 那个服务的confirm/cancel接口
 *
 * confirm或者cancel执行失败的时候，bytetcc框架是不停的，永不停歇的重试confirm和cancel接口的机制，就揭示清楚了
 *
 * 刚启动的时候，就会先去恢复一次事务，后面才会开始尝试每隔60s去恢复一次未完成的事务。
 *
 * 恢复事务也是根据本地记录的活动日志，发起comfirm或cancel接口的调用
 **/
public class CompensableWork implements Work, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableWork.class);

	static final long SECOND_MILLIS = 1000L;
	private long stopTimeMillis = -1;
	private long delayOfStoping = SECOND_MILLIS * 15;
	private long recoveryInterval = SECOND_MILLIS * 60;

	private boolean initialized = false;

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;

	private void initializeIfNecessary() {
	    // 该组件专门用于事务恢复的，实现类是：TransactionRecoveryImpl
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		if (this.initialized == false) {
			try {
				compensableRecovery.startRecovery();
				this.initialized = true;
				compensableRecovery.timingRecover();
			} catch (RuntimeException rex) {
				logger.error("Error occurred while initializing the compensable work.", rex);
			}
		}
	}

	public void run() {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();

		// 启动时恢复一次
		this.initializeIfNecessary();

		long nextRecoveryTime = 0;
		while (this.currentActive()) {

			this.initializeIfNecessary();

			long current = System.currentTimeMillis();
			// 每隔60s执行一次，进入if中执行compensableRecovery.timingRecover();逻辑
			if (current >= nextRecoveryTime) {
				nextRecoveryTime = current + this.recoveryInterval;

				try {
				    // 内部走recoverCommit()，然后是fireNativeParticipantConfirm(),即执行SpringCloudCoordinator的方法，去发送一个http请求，调用/commit或/cancel接口
					compensableRecovery.timingRecover();
				} catch (RuntimeException rex) {
					logger.error(rex.getMessage(), rex);
				}
			}

			this.waitForMillis(100L);

		} // end-while (this.currentActive())
	}

	private void waitForMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception ignore) {
			logger.debug(ignore.getMessage(), ignore);
		}
	}

	public void release() {
		this.stopTimeMillis = System.currentTimeMillis() + this.delayOfStoping;
	}

	protected boolean currentActive() {
		return this.stopTimeMillis <= 0 || System.currentTimeMillis() < this.stopTimeMillis;
	}

	public long getDelayOfStoping() {
		return delayOfStoping;
	}

	public long getRecoveryInterval() {
		return recoveryInterval;
	}

	public void setRecoveryInterval(long recoveryInterval) {
		this.recoveryInterval = recoveryInterval;
	}

	public void setDelayOfStoping(long delayOfStoping) {
		this.delayOfStoping = delayOfStoping;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
