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
 * 可能就1个服务的confirm失败了，此时CompensableWork会每隔一段时间，定时不断的去重试那个服务的confirm接口
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

		this.initializeIfNecessary();

		long nextRecoveryTime = 0;
		while (this.currentActive()) {

			this.initializeIfNecessary();

			long current = System.currentTimeMillis();
			// 每隔60s才能进入if中执行compensableRecovery.timingRecover();逻辑
			if (current >= nextRecoveryTime) {
				nextRecoveryTime = current + this.recoveryInterval;

				try {
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
