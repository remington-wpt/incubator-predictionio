package io.prediction.core

import scala.reflect.Manifest
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf

// FIXME(yipjustin). I am being lazy...
import io.prediction._

abstract class BaseDataPreparator[
    EDP <: BaseParams : Manifest,
    TDP <: BaseParams,
    VDP <: BaseParams,
    BTD, 
    F,
    A]
  extends AbstractParameterizedDoer[EDP] {

  type BTDP = BaseParams
  type BVDP = BaseParams

  def prepareBase(sc: SparkContext, params: BaseParams)
  : Map[Int, (BTDP, BVDP, BTD, RDD[(F, A)])] = {
    prepare(sc, params.asInstanceOf[EDP])
    /*
    prepare(sc, params.asInstanceOf[EDP]).map { case (ei, e) => {
      (ei -> e)
    }}
    */
  }

  def prepare(sc: SparkContext, params: EDP)
  : Map[Int, (BTDP, BVDP, BTD, RDD[(F, A)])]
}


abstract class SlicedDataPreparator[
    EDP <: BaseParams : Manifest,
    TDP <: BaseParams,
    VDP <: BaseParams,
    BTD, 
    F,
    A]
  extends BaseDataPreparator[EDP, TDP, VDP, BTD, F, A] {

  def prepare(sc: SparkContext, params: EDP)
  : Map[Int, (BTDP, BVDP, BTD, RDD[(F, A)])] = {
    val localParamsSet
    : Map[Int, (BaseParams, BaseParams)] =
      getParamsSetBase(params)
      .zipWithIndex
      .map(_.swap)
      .toMap

    // May add a param to skip .par
    val evalDataMap
    : Map[Int, (BTD, RDD[(F, A)])] = localParamsSet
    .par
    .map{ case (ei, localParams) => {
      val (localTrainingParams, localValidationParams) = localParams

      val trainingData = prepareTrainingBase(sc, localTrainingParams)
      val validationData = prepareValidationBase(sc, localValidationParams)
      (ei, (trainingData, validationData))
    }}
    .seq
    .toMap

    evalDataMap.map { case(ei, e) => {
      val params = localParamsSet(ei)
      (ei, (params._1, params._2, e._1, e._2))
    }}
    .toMap
  }

  def getParamsSetBase(params: BaseParams)
  : Seq[(TDP, VDP)] = getParamsSet(params.asInstanceOf[EDP])

  def getParamsSet(params: EDP): Seq[(TDP, VDP)] 
  def prepareTrainingBase(
    sc: SparkContext,
    params: BaseParams): BTD = {
    prepareTraining(sc, params.asInstanceOf[TDP])
  }
  
  def prepareTraining(sc: SparkContext, params: TDP): BTD
  
  def prepareValidationBase(
    sc: SparkContext,
    params: BaseParams): RDD[(F, A)] = {
    prepareValidation(sc, params.asInstanceOf[VDP])
  }
  
  def prepareValidation(sc: SparkContext, params: VDP): RDD[(F, A)]
}


abstract class LocalDataPreparator[
    EDP <: BaseParams : Manifest,
    TDP <: BaseParams : Manifest,
    VDP <: BaseParams,
    TD : Manifest, F, A]
    extends SlicedDataPreparator[EDP, TDP, VDP, RDD[TD], F, A] {

  override
  def prepareTrainingBase(
    sc: SparkContext,
    params: BaseParams): RDD[TD] = {
    println("LocalDataPreparator.prepareTrainingBase")
    val tdp = params.asInstanceOf[TDP]
    prepareTraining(sc, tdp)
  }

  def prepareTraining(sc: SparkContext, tdp: TDP): RDD[TD] = {
    val sParams = sc.parallelize(Array(tdp))
    sParams.map(prepareTraining)
  }

  def prepareTraining(params: TDP): TD

  override
  def prepareValidationBase(
    sc: SparkContext,
    params: BaseParams): RDD[(F, A)] = {
    val vdp = params.asInstanceOf[VDP]
    prepareValidation(sc, vdp)
  }

  def prepareValidation(sc: SparkContext, vdp: VDP): RDD[(F, A)] = {
    sc.parallelize(prepareValidation(vdp))
  }
  
  def prepareValidation(params: VDP): Seq[(F, A)]
}

// In this case, TD may contain multiple RDDs
// But still, F and A cannot contain RDD
abstract class SparkDataPreparator[
    EDP <: BaseParams : Manifest,
    TDP <: BaseParams : Manifest,
    VDP <: BaseParams,
    TD : Manifest, F, A]
  extends SlicedDataPreparator[EDP, TDP, VDP, TD, F, A] {

  override
  def prepareTrainingBase(
    sc: SparkContext,
    params: BaseParams): TD = {
    println("SparkDataPreparator.prepareTrainingBase")
    val tdp = params.asInstanceOf[TDP]
    prepareTraining(sc, tdp)
  }

  def prepareTraining(sc: SparkContext, params: TDP): TD

  override
  def prepareValidationBase(
    sc: SparkContext,
    params: BaseParams): RDD[(F, A)] = {
    val vdp = params.asInstanceOf[VDP]
    prepareValidation(sc, vdp)
  }
  
  def prepareValidation(sc: SparkContext, params: VDP): RDD[(F, A)]
}

abstract class BaseValidator[
    VP <: BaseParams : Manifest,
    TDP <: BaseParams,
    VDP <: BaseParams,
    F, P, A, VU, VR, CVR <: AnyRef]
  extends AbstractParameterizedDoer[VP] {

  def validateBase(input: (F, P, A)): VU = {
    validate(input._1, input._2, input._3)
  }
 
  def validate(feature: F, predicted: P, actual: A): VU

  def validateSetBase(
    trainingDataParams: BaseParams,
    validationDataParams: BaseParams,
    validationUnits: Seq[Any]): VR = {
    validateSet(
      trainingDataParams.asInstanceOf[TDP],
      validationDataParams.asInstanceOf[VDP],
      validationUnits.map(_.asInstanceOf[VU]))
  }

  def validateSet(
    trainingDataParams: TDP,
    validationDataParams: VDP,
    validationUnits: Seq[VU]): VR

  def crossValidateBase(
    input: Seq[(BaseParams, BaseParams,
      Any)]): CVR = {
    crossValidate(input.map(e => (
      e._1.asInstanceOf[TDP],
      e._2.asInstanceOf[VDP],
      e._3.asInstanceOf[VR])))
  }

  def crossValidate(validateResultsSeq: Seq[(TDP, VDP, VR)]): CVR
}

/* Evaluator */
class BaseEvaluator[
    EDP <: BaseParams,
    VP <: BaseParams,
    TDP <: BaseParams,
    VDP <: BaseParams,
    TD, F, P, A, VU, VR, CVR <: AnyRef](
  val dataPreparatorClass
    : Class[_ <: BaseDataPreparator[EDP, TDP, VDP, TD, F, A]],
  val validatorClass
    : Class[_ <: BaseValidator[VP, TDP, VDP, F, P, A, VU, VR, CVR]]) {}
