/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.springcloud.controller;

import java.beans.PropertyEditorSupport;

import javax.servlet.http.HttpServletResponse;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
// 这个是bytetcc提供的接口，用于调用本地的try，confirm，cancel接口实现和调用方的通信。否则调用方怎么调用我们写好的tcc三个接口呢？

// 这个controller里面对外暴露了一些接口，其实我们可以想象一下，prepare、commit、rollback、forget、recover，
// 分别对应着事务的try，事务的confirm，事务的cancel，事务的forget忽略，事务的恢复

// 如果利用feign通信：然后我们各个接口进行调用的时候，不是用的那个spring cloud的feign，然后人家框架对spring cloud feign尤其特别做了很多的扩展、拦截器、动态代理
        //spring cloud feign在进行调用的时候，人家完全重写了一些逻辑，比如说你请求的是某个接口，结果人家给重新定位到了请求那个服务的CompensableCoordinatorController，来进行某个接口的try、confirm、cancel
@Controller
public class CompensableCoordinatorController extends PropertyEditorSupport implements CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableCoordinatorController.class);

	@Autowired
	private CompensableCoordinator compensableCoordinator;
	@Autowired
	private CompensableBeanFactory beanFactory;

	// 对应try
	@RequestMapping(value = "/org/bytesoft/bytetcc/prepare/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public int prepare(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			return this.compensableCoordinator.prepare(xid);
		} catch (XAException ex) {
			logger.error("Error occurred while preparing transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
			return -1;
		} catch (RuntimeException ex) {
			logger.error("Error occurred while preparing transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.setStatus(500);
			return -1;
		}
	}

	// 事务提交，对应confirm
	@RequestMapping(value = "/org/bytesoft/bytetcc/commit/{xid}/{opc}", method = RequestMethod.POST)
	@ResponseBody
	public void commit(@PathVariable("xid") String identifier, @PathVariable("opc") boolean onePhase,
			HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.commit(xid, onePhase);
		} catch (XAException ex) {
			logger.error("Error occurred while committing transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			logger.error("Error occurred while committing transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	// 回滚接口，对应cancel
	@RequestMapping(value = "/org/bytesoft/bytetcc/rollback/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public void rollback(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.rollback(xid);
		} catch (XAException ex) {
			logger.error("Error occurred while rolling back transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			logger.error("Error occurred while rolling back transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	// 事务恢复接口
	@RequestMapping(value = "/org/bytesoft/bytetcc/recover/{flag}", method = RequestMethod.GET)
	@ResponseBody
	public Xid[] recover(@PathVariable("flag") int flag, HttpServletResponse response) {
		try {
			return this.compensableCoordinator.recover(flag);
		} catch (XAException ex) {
			logger.error("Error occurred while recovering transactions.", ex);

			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
			return new Xid[0];
		} catch (RuntimeException ex) {
			logger.error("Error occurred while recovering transactions.", ex);

			response.addHeader("failure", "true");
			response.setStatus(500);
			return new Xid[0];
		}
	}

	@RequestMapping(value = "/org/bytesoft/bytetcc/forget/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public void forget(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.forget(xid);
		} catch (XAException ex) {
			logger.error("Error occurred while forgetting transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			logger.error("Error occurred while forgetting transaction: {}.", identifier, ex);

			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
