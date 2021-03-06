/*
 * Copyright (c) 2020 De Staat der Nederlanden, Ministerie van Volksgezondheid, Welzijn en Sport.
 *  Licensed under the EUROPEAN UNION PUBLIC LICENCE v. 1.2
 *
 *  SPDX-License-Identifier: EUPL-1.2
 */
package nl.rijksoverheid.en.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.transition.TransitionInflater
import nl.rijksoverheid.en.BaseFragment
import nl.rijksoverheid.en.ExposureNotificationsViewModel
import nl.rijksoverheid.en.R
import nl.rijksoverheid.en.databinding.FragmentEnableApiBinding

class EnableApiFragment : BaseFragment(R.layout.fragment_enable_api) {
    private val onboardingViewModel: OnboardingViewModel by viewModels()
    private val viewModel: ExposureNotificationsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = TransitionInflater.from(context).inflateTransition(R.transition.slide_end)
        exitTransition =
            TransitionInflater.from(context).inflateTransition(R.transition.slide_start)

        sharedElementEnterTransition =
            TransitionInflater.from(context).inflateTransition(R.transition.move_fade)
        sharedElementReturnTransition = sharedElementEnterTransition
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentEnableApiBinding.bind(view)

        binding.explanation.setOnClickListener {
            enterTransition = null
            exitTransition = null
            sharedElementEnterTransition = null
            sharedElementReturnTransition = null
            findNavController().navigate(EnableApiFragmentDirections.actionExplain())
        }
        binding.skip.setOnClickListener {
            onboardingViewModel.finishOnboarding()
        }
        binding.request.setOnClickListener {
            viewModel.requestEnableNotifications()
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        viewModel.notificationState.observe(viewLifecycleOwner) {
            if (it is ExposureNotificationsViewModel.NotificationsState.Enabled) {
                onboardingViewModel.finishOnboarding()
            }
        }

        onboardingViewModel.onboardingComplete.observe(viewLifecycleOwner) {
            enterTransition = null
            exitTransition = null
            findNavController().popBackStack(R.id.nav_onboarding, true)
        }
    }
}
